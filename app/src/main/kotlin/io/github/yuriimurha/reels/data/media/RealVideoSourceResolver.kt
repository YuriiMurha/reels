package io.github.yuriimurha.reels.data.media

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import io.github.yuriimurha.reels.data.db.MediaDao
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.Pacer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Video links on demand (spec 8). A stored link is used as it is while it has more than [FRESH_MARGIN_MS] left; otherwise
 * one `mediaInfo` request on the Pacer's interactive lane ([PacedMediaInfo]) renews it. Bytes are cached under the media pk
 * ([VideoSource.Play.cacheKey]), so a renewed link still finds what was already downloaded.
 *
 * A video that is fully on disk plays from its stored link whatever that link's age: no request (R78). Without a valid
 * session no request is made either (R76). When Instagram cannot be asked (no session, cooling down, over budget, offline)
 * anything else gets a message the viewer shows over the thumbnail, next to Open on Instagram.
 */
@OptIn(UnstableApi::class)
class RealVideoSourceResolver(
    client: InstagramClient,
    pacer: Pacer,
    private val mediaDao: MediaDao,
    private val cache: VideoCache,
    signals: SessionSignals,
    /**
     * True only while the stored session is valid. Without one (logged out, expired, a challenge) nothing is sent to
     * Instagram, however the resolve was started: spec 6.4 allows no automatic request after a challenge (R76). Asked twice:
     * before the request queues, and again from inside the Pacer's gate, because the wait for the gate can take seconds (R79).
     */
    isSessionReady: suspend () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VideoSourceResolver {
    /** The one `mediaInfo` request, on the interactive lane under the session gates (R76, R79). */
    private val info = PacedMediaInfo(client, pacer, signals, isSessionReady)

    override suspend fun resolve(media: MediaEntity, forceRefresh: Boolean): VideoSource? {
        if (!media.isVideo) return null
        var current = media
        return try {
            // The row is the truth: a link renewed a moment ago (by a prefetch) may not have reached the caller's paging snapshot yet.
            current = mediaDao.byPks(listOf(media.pk)).firstOrNull() ?: media
            if (!forceRefresh) {
                if (!current.videoLinkNeedsRefresh(now())) return current.stored()
                // The bytes are on disk, so the link that names them is beside the point (R78).
                current.cachedPlay()?.let { return it }
            }
            when (val outcome = info.fetch(current.pk)) {
                is PacedMediaInfo.Outcome.Fresh -> {
                    val url = outcome.media.videoUrl ?: return GONE
                    // A link whose URL names no expiry is assumed good for an hour, so it cannot cost a request on every settle (R78).
                    val expiresAt = outcome.media.videoUrlExpiresAt?.toEpochMilli() ?: (now() + ASSUMED_LIFETIME_MS)
                    mediaDao.setVideoLink(current.pk, url, expiresAt)
                    VideoSource.Play(url.toUri(), current.pk)
                }
                PacedMediaInfo.Outcome.Gone -> GONE
                is PacedMediaInfo.Outcome.Failed -> when (outcome.reason) {
                    // Nothing was sent (no usable session, before the gate or inside it, R76/R79); cached bytes still play.
                    PacedMediaInfo.Reason.NO_SESSION -> current.cachedPlay() ?: NEEDS_ATTENTION
                    PacedMediaInfo.Reason.REFUSED ->
                        current.cachedOrElse { VideoSource.Unavailable("Video can't load right now: ${outcome.message}") }
                    PacedMediaInfo.Reason.OFFLINE -> current.cachedOrElse { OFFLINE }
                    PacedMediaInfo.Reason.NEEDS_ATTENTION -> NEEDS_ATTENTION
                    PacedMediaInfo.Reason.RATE_LIMITED -> VideoSource.Unavailable(outcome.message)
                    PacedMediaInfo.Reason.UNEXPECTED -> CANT_LOAD
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            current.cachedOrElse { OFFLINE }
        } catch (e: Exception) {
            // A database failure, anything unforeseen: the viewer shows a message, it never crashes. The text of an unexpected
            // failure is not shown (it may hold session data).
            CANT_LOAD
        }
    }

    /** The stored link, unchanged: it was just judged fresh enough, so it is not null. */
    private fun MediaEntity.stored(): VideoSource = VideoSource.Play(checkNotNull(videoUrl).toUri(), pk)

    /**
     * The stored link when the whole video is cached and the link can still name it (the player needs a URI), else null.
     * Off the caller's thread: `SimpleCache` synchronises its reads with its file commits, which can hold a lock for as long
     * as a download is being finished, and the viewer calls this from the main thread.
     */
    private suspend fun MediaEntity.cachedPlay(): VideoSource? {
        val url = videoUrl ?: return null
        val pk = pk
        return if (withContext(ioDispatcher) { cache.isFullyCached(pk) }) VideoSource.Play(url.toUri(), pk) else null
    }

    /** [cachedPlay], or [otherwise] when the request failed and nothing is cached. */
    private suspend inline fun MediaEntity.cachedOrElse(otherwise: () -> VideoSource): VideoSource = cachedPlay() ?: otherwise()

    companion object {
        /** A stored link with at most this long left is renewed before it is used (spec 8.2). */
        const val FRESH_MARGIN_MS = 10 * 60_000L

        /** What a renewed link with no expiry of its own is assumed to live for (R78). */
        const val ASSUMED_LIFETIME_MS = 60 * 60_000L

        private val GONE = VideoSource.Unavailable("This item is no longer available on Instagram")
        private val OFFLINE = VideoSource.Unavailable("Offline: this video isn't cached yet")
        private val NEEDS_ATTENTION = VideoSource.Unavailable(PacedMediaInfo.NEEDS_ATTENTION_MESSAGE)
        private val CANT_LOAD = VideoSource.Unavailable("Can't load this video right now")
    }
}
