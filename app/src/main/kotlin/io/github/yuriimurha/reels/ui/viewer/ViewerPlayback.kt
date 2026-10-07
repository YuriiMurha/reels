package io.github.yuriimurha.reels.ui.viewer

import androidx.media3.datasource.HttpDataSource
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.media.VideoSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** What the viewer shows under a video that cannot play: the thumbnail, this, and Open on Instagram (spec 8.5). */
internal const val CANT_PLAY = "Can't play this video"

/** The player's item: the link to read, and the media pk as cache key so a refreshed link still finds the cached bytes (spec 8.3). */
@OptIn(UnstableApi::class)
fun VideoSource.Play.toMediaItem(): MediaItem = MediaItem.Builder().setUri(uri).setCustomCacheKey(cacheKey).build()

/** The pager's settled page: its [index] and the item on it ([media] is null while that row is still a placeholder). */
data class SettledPage(val index: Int, val media: MediaEntity?)

/**
 * Resolves [media]'s video and hands the answer to [onSource], but only if [isStillSettled] says that item is still the one
 * on screen once the answer is in. A resolve can take seconds (a paced request); the owner may have swiped on by then, and a
 * video that starts under the wrong page is worse than a late one. Null (not a video) is not handed over.
 */
suspend fun playIfStillSettled(
    media: MediaEntity,
    resolve: suspend (MediaEntity) -> VideoSource?,
    isStillSettled: (MediaEntity) -> Boolean,
    onSource: (VideoSource) -> Unit,
) {
    val source = resolve(media) ?: return
    // A resolve that cannot be cancelled returns even after a newer page cancelled this one: do not act on it.
    currentCoroutineContext().ensureActive()
    if (isStillSettled(media)) onSource(source)
}

/**
 * Follows the settled pages. Collected with `collectLatest`, so settling on a newer page cancels the resolve of the older
 * one instead of queueing behind it; [playIfStillSettled] covers an answer that arrives in the same instant.
 * [onSettled] runs first for every settle (stop the player, report the index, start the prefetch).
 */
suspend fun playSettledPages(
    settled: Flow<SettledPage>,
    resolve: suspend (MediaEntity) -> VideoSource?,
    isStillSettled: (MediaEntity) -> Boolean,
    onSettled: (SettledPage) -> Unit,
    onSource: (MediaEntity, VideoSource) -> Unit,
) {
    settled.collectLatest { page ->
        onSettled(page)
        val media = page.media ?: return@collectLatest
        playIfStillSettled(media, resolve, isStillSettled) { source -> onSource(media, source) }
    }
}

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
