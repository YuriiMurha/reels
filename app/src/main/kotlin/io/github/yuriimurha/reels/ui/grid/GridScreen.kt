package io.github.yuriimurha.reels.ui.grid

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.MediaGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(
    source: MediaSource,
    title: String,
    returnedIndex: Int?,
    onBack: () -> Unit,
    onOpenViewer: (Int) -> Unit,
    onReturnedIndexConsumed: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel(key = "grid:${source.encode()}") { GridViewModel(source, container.library) }
    val items = viewModel.items.collectAsLazyPagingItems()
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(returnedIndex) {
        if (returnedIndex != null) {
            gridState.scrollToItem(returnedIndex)
            // Consume it once, so a recreation of this entry doesn't jump back to a stale index.
            onReturnedIndexConsumed()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        MediaGrid(
            items = items,
            onOpen = onOpenViewer,
            modifier = Modifier.padding(horizontal = 8.dp),
            state = gridState,
            contentPadding = padding,
        )
    }
}
