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
 * A video that is fully on disk plays from its stored link whatever that link's age: no request (R78). Without a valid
 * session no request is made either (R76). When Instagram cannot be asked (no session, cooling down, over budget, offline)
 * anything else gets a message the viewer shows over the thumbnail, next to Open on Instagram.
 */
@OptIn(UnstableApi::class)
class RealVideoSourceResolver(
    private val client: InstagramClient,
    private val pacer: Pacer,
    private val mediaDao: MediaDao,
    private val cache: VideoCache,
    private val signals: SessionSignals,
    /**
     * True only while the stored session is valid. Without one (logged out, expired, a challenge) nothing is sent to
     * Instagram, however the resolve was started: spec 6.4 allows no automatic request after a challenge (R76).
     */
    private val isSessionReady: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : VideoSourceResolver {
    override suspend fun resolve(media: MediaEntity, forceRefresh: Boolean): VideoSource? {
        if (!media.isVideo) return null
        // The session this request starts under: a failure that outlives a logout or a paste must not expire the login after it.
        val epoch = signals.epoch()
        var current = media
        return try {
            // The row is the truth: a link renewed a moment ago (by a prefetch) may not have reached the caller's paging snapshot yet.
            current = mediaDao.byPks(listOf(media.pk)).firstOrNull() ?: media
            if (!forceRefresh) {
                if (!current.videoLinkNeedsRefresh(now())) return current.stored()
                // The bytes are on disk, so the link that names them is beside the point (R78).
                current.cachedPlay()?.let { return it }
            }
            // Nothing may go to Instagram without a usable session (R76); cached bytes still play.
            if (!isSessionReady()) return current.cachedPlay() ?: NEEDS_ATTENTION
            val fresh = pacer.interactive { client.mediaInfo(current.pk) }
            val url = fresh?.videoUrl ?: return GONE
            // A link whose URL names no expiry is assumed good for an hour, so it cannot cost a request on every settle (R78).
            val expiresAt = fresh.videoUrlExpiresAt?.toEpochMilli() ?: (now() + ASSUMED_LIFETIME_MS)
            mediaDao.setVideoLink(current.pk, url, expiresAt)
            VideoSource.Play(url.toUri(), current.pk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PacerRefusal) {
            current.cachedOrElse { VideoSource.Unavailable("Video can't load right now: ${e.userMessage("try again later")}") }
        } catch (e: InstagramException.Transient) {
            current.cachedOrElse { OFFLINE }
        } catch (e: IOException) {
            current.cachedOrElse { OFFLINE }
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

    /** The stored link when the whole video is cached and the link can still name it (the player needs a URI), else null. */
    private fun MediaEntity.cachedPlay(): VideoSource? {
        val url = videoUrl
        return if (url != null && cache.isFullyCached(pk)) VideoSource.Play(url.toUri(), pk) else null
    }

    /** [cachedPlay], or [otherwise] when the request failed and nothing is cached. */
    private inline fun MediaEntity.cachedOrElse(otherwise: () -> VideoSource): VideoSource = cachedPlay() ?: otherwise()

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

        /** What a renewed link with no expiry of its own is assumed to live for (R78). */
        const val ASSUMED_LIFETIME_MS = 60 * 60_000L

        private val GONE = VideoSource.Unavailable("This item is no longer available on Instagram")
        private val OFFLINE = VideoSource.Unavailable("Offline: this video isn't cached yet")
        private val NEEDS_ATTENTION = VideoSource.Unavailable("Instagram session needs attention (Sync screen)")
    }
}
