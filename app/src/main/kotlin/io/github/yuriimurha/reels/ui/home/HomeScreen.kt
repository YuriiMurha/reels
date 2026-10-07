package io.github.yuriimurha.reels.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.UNCATEGORIZED_ID
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.SyncStatusChip
import io.github.yuriimurha.reels.ui.common.SyncStatusSummary
import io.github.yuriimurha.reels.ui.common.Thumbnail

@Composable
fun HomeScreen(
    onOpenSource: (MediaSource, String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { HomeViewModel(container.library, container.syncController) }
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    HomeContent(
        cards = cards,
        status = status,
        onOpenCard = { card -> onOpenSource(card.toSource(), card.name) },
        onOpenSearch = onOpenSearch,
        onOpenSync = onOpenSync,
    )
}

private fun CollectionCard.toSource(): MediaSource =
    if (id == UNCATEGORIZED_ID) MediaSource.Uncategorized else MediaSource.Collection(id)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    cards: List<CollectionCard>?,
    status: SyncStatusSummary,
    onOpenCard: (CollectionCard) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSync: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved") },
                actions = {
                    IconButton(onClick = onOpenSearch) { Icon(Icons.Default.Search, contentDescription = "Search") }
                    SyncStatusChip(status, onOpenSync)
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        when {
            cards == null -> Unit
            cards.isEmpty() -> EmptyLibrary(onOpenSync, Modifier.padding(padding))
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = padding,
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(cards, key = { it.id }) { card -> CollectionCardView(card, onClick = { onOpenCard(card) }) }
            }
        }
    }
}

/**
 * `clickable` merges descendant semantics, so a tag on the name Text would not survive into the merged tree. The tag
 * therefore sits on the clickable card itself, whose merged text is the name alone: the count is exposed to
 * accessibility as a content description instead of a second text.
 */
@Composable
private fun CollectionCardView(card: CollectionCard, onClick: () -> Unit) {
    val countLabel = if (card.count == 1) "1 item" else "${card.count} items"
    Column(Modifier.testTag("collection-name").clickable(onClickLabel = "Open ${card.name}", onClick = onClick)) {
        Thumbnail(card.coverThumbPath, Modifier.fillMaxWidth().aspectRatio(4f / 5f).clip(RoundedCornerShape(14.dp)))
        Text(
            card.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            countLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clearAndSetSemantics { contentDescription = countLabel },
        )
    }
}

@Composable
private fun EmptyLibrary(onOpenSync: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing synced yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sync pulls your saved reels and collections onto this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onOpenSync, modifier = Modifier.padding(top = 16.dp)) { Text("Open Sync") }
    }
}
