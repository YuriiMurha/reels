package io.github.yuriimurha.reels.ui.viewer

import androidx.media3.datasource.HttpDataSource
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.media.VideoSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** What the viewer shows under a video that cannot play: the thumbnail, this, and Open on Instagram (spec 8.5). */
internal const val CANT_PLAY = "Can't play this video"

/** What a carousel item that cannot show or play says, with Open on Instagram (spec 2026-10-12 §3.3). */
internal const val CANT_LOAD_ITEM = "Can't load this item"

/**
 * The player's item: the link to read, and the cache key (the media pk, or `pk_position` for a carousel item) so a refreshed link
 * still finds the cached bytes (spec 8.3).
 */
@OptIn(UnstableApi::class)
fun VideoSource.Play.toMediaItem(): MediaItem = MediaItem.Builder().setUri(uri).setCustomCacheKey(cacheKey).build()

/**
 * The playable unit: a post, or the item at [position] of a carousel post ([position] is null for any other post). The player
 * only ever has the unit whose post AND item are both settled (spec 2026-10-12 §3.2).
 */
data class Playable(val pk: String, val position: Int?)

/**
 * The settled page: the vertical pager's [index], the post on it ([media] is null while that row is still a placeholder), and
 * for a carousel post the [position] its own pager settled on (null for any other post).
 */
data class SettledPage(val index: Int, val media: MediaEntity?, val position: Int? = null) {
    /** The unit to play here, or null while the page is a placeholder. */
    val playable: Playable? get() = media?.let { Playable(it.pk, position) }
}

/**
 * The settled page as the screen reports it, or null while its list and its pager disagree (C1). [media] is the list's row at
 * [index] (null while it is a placeholder) and [laidOutKey] the key the pager laid out at [index] (null before its first
 * layout). A sync that adds or removes a post above the settled one changes the list at once, but the pager moves the post's
 * key to its new index only in its next measure: a loaded row whose pk is not the laid-out key is that instant, and no settle.
 * A placeholder is never held back: its key is Paging's own, never a pk, and its settle must still stop the previous video.
 */
fun settledPageOrNull(index: Int, media: MediaEntity?, laidOutKey: Any?, position: Int?): SettledPage? =
    if (media != null && laidOutKey != null && laidOutKey != media.pk) null else SettledPage(index, media, position)

/**
 * Resolves [unit]'s video and hands the answer to [onSource], but only if [isStillSettled] says that unit is still the one
 * on screen once the answer is in. A resolve can take seconds (a paced request); the owner may have swiped on by then, and a
 * video that starts under the wrong page (or item) is worse than a late one. Null (not a video) is not handed over.
 */
suspend fun <T> playIfStillSettled(
    unit: T,
    resolve: suspend (T) -> VideoSource?,
    isStillSettled: (T) -> Boolean,
    onSource: (VideoSource) -> Unit,
) {
    val source = resolve(unit) ?: return
    // A resolve that cannot be cancelled returns even after a newer page cancelled this one: do not act on it.
    currentCoroutineContext().ensureActive()
    if (isStillSettled(unit)) onSource(source)
}

/**
 * Follows the settled units: a new post, or a new item of the settled carousel. Collected with `collectLatest`, so settling
 * on a newer unit cancels the resolve of the older one instead of queueing behind it; [playIfStillSettled] covers an answer
 * that arrives in the same instant. [onSettled] runs first for every settle (stop the player, report the index), so settling
 * on an image item stops the video of the item before it; [afterResolved] runs once the settled unit's own resolve has
 * returned (the next post's link is prefetched there, never before: the visible one goes first, R77). A unit that was swiped
 * past before its resolve returned never reaches [afterResolved].
 */
suspend fun playSettledPages(
    settled: Flow<SettledPage>,
    resolve: suspend (media: MediaEntity, position: Int?) -> VideoSource?,
    isStillSettled: (Playable) -> Boolean,
    onSettled: (SettledPage) -> Unit,
    afterResolved: (SettledPage) -> Unit,
    onSource: (SettledPage, VideoSource) -> Unit,
) {
    settled.collectLatest { page ->
        onSettled(page)
        page.media?.let { media ->
            val unit = Playable(media.pk, page.position)
            playIfStillSettled(unit, { resolve(media, it.position) }, isStillSettled) { source -> onSource(page, source) }
        }
        afterResolved(page)
    }
}

/**
 * True when [error] is the player not reaching the server at all: the connection failed (no network, no route, an unknown
 * host) or timed out. A carousel video item whose bytes are not all cached then says it is offline (R16); a refused link or a
 * decoder failure is not this.
 */
fun isNetworkError(error: Throwable): Boolean =
    (error as? PlaybackException)?.errorCode in NETWORK_ERROR_CODES

private val NETWORK_ERROR_CODES =
    setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)

/**
 * True when [error] (or anything in its cause chain) is the CDN refusing a link with 403 or 410: the link expired or was
 * revoked, so asking Instagram for a new one may help. Any other failure (a 404, a decoder, the network) will not be
 * cured by a new link.
 */
fun isExpiredLinkError(error: Throwable): Boolean {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
        if (cause is HttpDataSource.InvalidResponseCodeException && (cause.responseCode == 403 || cause.responseCode == 410)) return true
        cause = cause.cause
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 12
