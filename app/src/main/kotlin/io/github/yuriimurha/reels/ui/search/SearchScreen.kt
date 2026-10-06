package io.github.yuriimurha.reels.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.TypeFilter
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.MediaGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenViewer: (MediaSource, Int) -> Unit,
    returnedIndex: Int? = null,
    onReturnedIndexConsumed: () -> Unit = {},
) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { SearchViewModel(container.library) }
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val results = viewModel.results.collectAsLazyPagingItems()
    val focus = remember { FocusRequester() }
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(returnedIndex) {
        if (returnedIndex != null) {
            gridState.scrollToItem(returnedIndex)
            onReturnedIndexConsumed()
        }
    }
    LaunchedEffect(query) {
        if (query.isEmpty()) focus.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { viewModel.query.value = it },
                        placeholder = { Text("Captions, authors, collections") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("searchField"),
                    )
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TypeFilter.entries) { type ->
                    FilterChip(selected = filter == type, onClick = { viewModel.filter.value = type }, label = { Text(type.label) })
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = scope == ALL_SAVED_ID,
                        onClick = { viewModel.scope.value = ALL_SAVED_ID },
                        label = { Text("All Saved") },
                    )
                }
                items(collections, key = { it.id }) { collection ->
                    FilterChip(
                        selected = scope == collection.id,
                        onClick = { viewModel.scope.value = collection.id },
                        label = { Text(collection.name) },
                    )
                }
            }
            val current = source
            when {
                current == null -> Hint("Search captions, authors and collections")
                results.itemCount == 0 && results.loadState.refresh is LoadState.NotLoading -> Hint("No matches")
                else -> MediaGrid(
                    items = results,
                    onOpen = { index -> onOpenViewer(current, index) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                    state = gridState,
                    contentPadding = padding,
                )
            }
        }
    }
}

private val TypeFilter.label: String
    get() = when (this) {
        TypeFilter.ALL -> "All"
        TypeFilter.REELS -> "Reels"
        TypeFilter.POSTS -> "Posts"
    }

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
