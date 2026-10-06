package io.github.yuriimurha.reels.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val ratio = media?.let { (it.width.toFloat() / it.height).coerceIn(0.5f, 1f) } ?: (9f / 16f)
    Column(modifier.testTag("tile").clickable(enabled = media != null, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(10.dp))) {
            Thumbnail(media?.thumbPath, Modifier.matchParentSize())
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

@Composable
private fun TypeBadge(media: MediaEntity, modifier: Modifier = Modifier) {
    val label = when (media.type) {
        MediaType.REEL, MediaType.VIDEO -> "▶"
        MediaType.CAROUSEL -> "1/${media.carouselCount ?: 1}"
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
