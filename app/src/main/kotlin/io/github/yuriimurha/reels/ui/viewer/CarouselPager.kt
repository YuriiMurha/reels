package io.github.yuriimurha.reels.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.ui.common.Thumbnail

/** Test tag of a carousel's horizontal pager (the area a left or right swipe moves). */
const val CAROUSEL_PAGER_TAG = "carousel-pager"

/** Test tag of a carousel's "N/M" counter (top end). */
const val CAROUSEL_COUNTER_TAG = "carousel-counter"

/** Test tag of a carousel's dots (bottom centre, above the overlay). */
const val CAROUSEL_DOTS_TAG = "carousel-dots"

/** Test tag of the stored cover, which item 0 shows at once (and every carousel shows while its items are unknown). */
const val CAROUSEL_COVER_TAG = "carousel-cover"

/** Test tag of the small loading mark shown over the cover while the items are being filled. */
const val CAROUSEL_LOADING_TAG = "carousel-loading"

/** Test tag of item [position]'s downloaded image. */
fun carouselImageTag(position: Int) = "carousel-image-$position"

/** Test tag of video item [position]'s downloaded poster (R26), which shows until the video's first frame. */
fun carouselPosterTag(position: Int) = "carousel-poster-$position"

/**
 * One item of a carousel as the viewer shows it. It has no width or height on purpose: a stored item's may be 0, or mixed
 * (Task 1), so the viewer never sizes from them; it fits each item to the screen ([ContentScale.Fit]) instead.
 */
data class CarouselItemUi(
    val position: Int,
    /** [MediaType.IMAGE] or [MediaType.VIDEO]. */
    val type: MediaType,
    /**
     * The downloaded picture (absolute path): an image item's image, or a video item's poster (R26), shown until its first
     * frame and under its failure. Null while there is none: item 0 shows the cover meanwhile (when the post has one; R29),
     * any other the viewer's black.
     */
    val imagePath: String? = null,
    /** Why the item cannot show or play ("Can't load this item", "Offline: this item isn't cached yet", ...), or null. */
    val failure: String? = null,
)

/**
 * The settled carousel post as its pager shows it (one visit, R8).
 *
 * @property items the post's items in position order; empty while they are being filled ([loading]) or when they could not be
 *   ([coverOnlyMessage]): the pager then has one page, the cover.
 * @property settled the page (item) the pager settled on; a new visit starts at 0, and the pager follows it there.
 * @property coverOnlyMessage why only the cover shows, or null.
 * @property loading true while the items are being filled: the cover shows with a small loading mark.
 */
data class CarouselUiState(
    val items: List<CarouselItemUi> = emptyList(),
    val settled: Int = 0,
    val coverOnlyMessage: String? = null,
    val loading: Boolean = false,
) {
    /** The pager's pages: one per item, or the cover alone while the items are unknown. */
    val pageCount: Int get() = items.size.coerceAtLeast(1)

    /** The settled item, or null while the items are unknown. */
    val settledItem: CarouselItemUi? get() = items.getOrNull(settled)

    /** The same state with [message] on the item at page [index] (a video item that cannot play); unchanged if there is none. */
    fun withItemFailure(index: Int, message: String): CarouselUiState =
        if (index !in items.indices) this else copy(items = items.mapIndexed { i, item -> if (i == index) item.copy(failure = message) else item })
}

/** M of the "N/M" counter: the stored items' count when there are any, else the post's own count (Task 1: never 0). */
fun carouselTotal(media: MediaEntity, state: CarouselUiState): Int =
    state.items.size.takeIf { it > 0 } ?: media.carouselCount?.coerceAtLeast(1) ?: 1

/**
 * The carousel's pager state, made on the visit's item ([CarouselUiState.settled]: 0 for a post shown again, the ViewModel's item
 * after a rotation), so a pager made again never reports item 0 first. Not saved: the lazy layout would bring a post back on its
 * old item; [CarouselPager] then follows the visit.
 */
@Composable
fun rememberCarouselPagerState(state: CarouselUiState): PagerState {
    val pageCount = rememberUpdatedState(state.pageCount)
    return remember { PagerState(currentPage = state.settled.coerceIn(0, state.pageCount - 1)) { pageCount.value } }
}

/**
 * A carousel post in the viewer (spec 2026-10-12 §3.2): its items side by side, swiped left and right inside the vertical post
 * pager, with an "N/M" counter at the top end. Item 0 shows the stored cover at once, so the first frame never waits for the
 * network; an image item shows its downloaded file once there is one (R7: until then its placeholder, the pager is never held
 * up); a video item draws [player] while it is the settled one (the screen gives [player] only to the unit that plays), with
 * its own poster (R26), or item 0's cover, as the frame's shutter: Media3's `ContentFrame` covers the surface with it until
 * the player renders this unit's first frame, and each item's frame is its own, so no item's picture covers another's video.
 * [onItemSettled] gets the page every time the pager settles. The dots are [CarouselDots], placed by the page with its overlay.
 */
@Composable
fun CarouselPager(
    media: MediaEntity,
    state: CarouselUiState,
    player: Player?,
    onItemSettled: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pagerState: PagerState = rememberCarouselPagerState(state),
    onTogglePlay: () -> Unit = {},
) {
    val reportSettled = rememberUpdatedState(onItemSettled)
    LaunchedEffect(pagerState) { snapshotFlow { pagerState.settledPage }.collect { reportSettled.value(it) } }
    // A new visit says 0 (or a rotation says where the visit was): the pager follows, unless the owner is swiping it.
    LaunchedEffect(pagerState, state.settled, state.pageCount) {
        if (state.settled in 0 until state.pageCount && pagerState.settledPage != state.settled && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(state.settled)
        }
    }
    Box(modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().testTag(CAROUSEL_PAGER_TAG)) { page ->
            val item = state.items.getOrNull(page)
            CarouselItemPage(
                media = media,
                item = item,
                index = page,
                player = player?.takeIf { page == state.settled && item?.type == MediaType.VIDEO },
                onTogglePlay = onTogglePlay,
                message = item?.failure ?: state.coverOnlyMessage?.takeIf { state.items.isEmpty() },
            )
        }
        Row(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.loading && state.items.isEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp).testTag(CAROUSEL_LOADING_TAG),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
            Text(
                "${pagerState.currentPage + 1}/${carouselTotal(media, state)}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .testTag(CAROUSEL_COUNTER_TAG)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** One page of the carousel: the playing video, the item's image, the cover (item 0) or the placeholder, and why it can't load. */
@Composable
private fun CarouselItemPage(
    media: MediaEntity,
    item: CarouselItemUi?,
    index: Int,
    player: Player?,
    onTogglePlay: () -> Unit,
    message: String?,
) {
    Box(Modifier.fillMaxSize()) {
        if (player != null) {
            VideoFrame(player, onTogglePlay) { ItemStill(media, item, index) }
        } else {
            ItemStill(media, item, index)
        }
        message?.let {
            Text(
                it,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * What an item shows when it is not drawing video, and a video item's shutter until its first frame: its picture (an image,
 * or a video item's poster, R26), else the cover for item 0, else the viewer's black.
 */
@Composable
private fun ItemStill(media: MediaEntity, item: CarouselItemUi?, index: Int) {
    val path = item?.imagePath
    when {
        path != null -> {
            val tag = if (item.type == MediaType.VIDEO) carouselPosterTag(index) else carouselImageTag(index)
            Thumbnail(path, Modifier.fillMaxSize().testTag(tag), ContentScale.Fit)
        }
        index == 0 && media.thumbPath != null ->
            Thumbnail(media.thumbPath, Modifier.fillMaxSize().testTag(CAROUSEL_COVER_TAG), version = media.thumbUrl, contentScale = ContentScale.Fit)
        else -> Box(Modifier.fillMaxSize())
    }
}

/** The carousel's dots, the current item's brighter (and selected, for tests and accessibility); nothing for fewer than two items. */
@Composable
fun CarouselDots(pagerState: PagerState, count: Int, modifier: Modifier = Modifier) {
    if (count < 2) return
    Row(modifier.testTag(CAROUSEL_DOTS_TAG), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(count) { i ->
            val current = i == pagerState.currentPage
            val alpha = if (current) 1f else 0.45f
            Box(Modifier.size(6.dp).background(Color.White.copy(alpha = alpha), CircleShape).semantics { selected = current })
        }
    }
}
