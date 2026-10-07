package io.github.yuriimurha.reels.data.media

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import io.github.yuriimurha.reels.data.db.MediaDao
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.session.userMessage
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerRefusal
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Video links on demand (spec 8). A stored link is used as it is while it has more than [FRESH_MARGIN_MS] left; otherwise
 * one `mediaInfo` request on the Pacer's interactive lane renews it. Bytes are cached under the media pk
 * ([VideoSource.Play.cacheKey]), so a renewed link still finds what was already downloaded.
 *
 * When Instagram cannot be asked (cooling down, over budget, offline) a video that is fully on disk still plays, from its
 * stored link; anything else gets a message the viewer shows over the thumbnail, next to Open on Instagram.
 */
@OptIn(UnstableApi::class)
class RealVideoSourceResolver(
    private val client: InstagramClient,
    private val pacer: Pacer,
    private val mediaDao: MediaDao,
    private val cache: VideoCache,
    private val signals: SessionSignals,
    private val now: () -> Long = System::currentTimeMillis,
) : VideoSourceResolver {
    override suspend fun resolve(media: MediaEntity, forceRefresh: Boolean): VideoSource? {
        if (!media.isVideo) return null
        if (!forceRefresh && !media.videoLinkNeedsRefresh(now())) return media.stored()
        // The session this request starts under: a failure that outlives a logout or a paste must not expire the login after it.
        val epoch = signals.epoch()
        return try {
            val fresh = pacer.interactive { client.mediaInfo(media.pk) }
            val url = fresh?.videoUrl ?: return GONE
            val expiresAt = fresh.videoUrlExpiresAt?.toEpochMilli()
            mediaDao.setVideoLink(media.pk, url, expiresAt)
            VideoSource.Play(url.toUri(), media.pk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PacerRefusal) {
            media.cachedOrElse { VideoSource.Unavailable("Video can't load right now: ${e.userMessage("try again later")}") }
        } catch (e: InstagramException.Transient) {
            media.cachedOrElse { OFFLINE }
        } catch (e: IOException) {
            media.cachedOrElse { OFFLINE }
        } catch (e: InstagramException.LoginRequired) {
            notify { signals.loginRequired(epoch) }
            NEEDS_ATTENTION
        } catch (e: InstagramException.ChallengeRequired) {
            notify { signals.challengeRequired(e.challengeUrl, epoch) }
            NEEDS_ATTENTION
        } catch (e: InstagramException.RateLimited) {
            // The Pacer has already armed the cooldown.
            VideoSource.Unavailable("Instagram is limiting requests")
        } catch (e: Exception) {
            // ShapeChanged, a database failure, anything unforeseen: the viewer shows a message, it never crashes. The text
            // of an unexpected failure is not shown (it may hold session data).
            VideoSource.Unavailable("Can't load this video right now")
        }
    }

    /** The stored link, unchanged: it was just judged fresh enough, so it is not null. */
    private fun MediaEntity.stored(): VideoSource = VideoSource.Play(checkNotNull(videoUrl).toUri(), pk)

    /** Plays from disk when the whole video is cached and the stored link can still name it; [otherwise] when not. */
    private inline fun MediaEntity.cachedOrElse(otherwise: () -> VideoSource): VideoSource {
        val url = videoUrl
        return if (url != null && cache.isFullyCached(pk)) VideoSource.Play(url.toUri(), pk) else otherwise()
    }

    /** A failing receiver must not change what the viewer shows; cancellation still propagates. */
    private suspend fun notify(signal: suspend () -> Unit) {
        try {
            signal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Intentionally ignored: the viewer already shows that the session needs attention.
        }
    }

    companion object {
        /** A stored link with at most this long left is renewed before it is used (spec 8.2). */
        const val FRESH_MARGIN_MS = 10 * 60_000L

        private val GONE = VideoSource.Unavailable("This item is no longer available on Instagram")
        private val OFFLINE = VideoSource.Unavailable("Offline: this video isn't cached yet")
        private val NEEDS_ATTENTION = VideoSource.Unavailable("Instagram session needs attention (Sync screen)")
    }
}
