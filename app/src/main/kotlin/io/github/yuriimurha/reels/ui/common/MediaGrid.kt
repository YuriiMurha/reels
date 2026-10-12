package io.github.yuriimurha.reels.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType

/** Two-column staggered grid over a paged list (spec 9.2). [onOpen] receives the absolute index. */
@Composable
fun MediaGrid(
    items: LazyPagingItems<MediaEntity>,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalItemSpacing = 12.dp,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count = items.itemCount, key = items.itemKey { it.pk }) { index ->
            MediaTile(media = items[index], onClick = { onOpen(index) })
        }
    }
}

@Composable
fun MediaTile(media: MediaEntity?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // An item stored without dimensions (0x0) would give NaN, and aspectRatio(NaN) throws; fall back to 9:16.
    val ratio = media?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { (it.width.toFloat() / it.height).coerceIn(0.5f, 1f) } ?: (9f / 16f)
    Column(modifier.testTag("tile").clickable(enabled = media != null, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(10.dp))) {
            // The post grows from here into the viewer, and shrinks back here (Motion.kt).
            Thumbnail(media?.thumbPath, Modifier.matchParentSize().then(if (media != null) Modifier.sharedMedia(media.pk) else Modifier))
            if (media != null) TypeBadge(media, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        if (media != null) {
            Text(
                "@${media.author}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            media.caption?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Brings the tile at [index] into view: the one of the post the viewer closed on, which the post shrinks back into (Motion.kt).
 * A tile already wholly in view stays where it is (going back from the post that was opened lands where it was tapped); a tile
 * partly hidden scrolls just enough; any other scrolls to the top. The scroll is instant, so the shrink retargets at once.
 */
suspend fun LazyStaggeredGridState.revealTile(index: Int) {
    // What is in view comes from the grid's first layout after its return. The caller's effect has started after it in every run
    // seen (JVM tests and the emulator); one frame's wait keeps that true should an effect ever start first, when an empty layout
    // would send even a tile in view to the top.
    withFrameNanos {}
    val info = layoutInfo
    val tile = info.visibleItemsInfo.firstOrNull { it.index == index }
    // Offsets count from the end of the top content padding (under the top bar); the viewport's end is the grid's bottom edge in
    // the same terms, and the bottom content padding (under the navigation bar) comes off it. MediaGridTest pins both.
    val end = info.viewportEndOffset - info.afterContentPadding
    when {
        tile == null -> scrollToItem(index)
        tile.offset.y < 0 -> scrollBy(tile.offset.y.toFloat())
        tile.offset.y + tile.size.height > end -> scrollBy((tile.offset.y + tile.size.height - end).toFloat())
    }
}

/** The "1/N" badge text on a carousel's grid tile (the viewer has its own "N/M" counter, `CarouselPager`). */
internal fun carouselLabel(media: MediaEntity): String = "1/${media.carouselCount ?: 1}"

@Composable
private fun TypeBadge(media: MediaEntity, modifier: Modifier = Modifier) {
    val label = when (media.type) {
        MediaType.REEL, MediaType.VIDEO -> "▶"
        MediaType.CAROUSEL -> carouselLabel(media)
        MediaType.IMAGE -> return
    }
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
