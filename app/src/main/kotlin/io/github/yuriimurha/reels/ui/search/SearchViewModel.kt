package io.github.yuriimurha.reels.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.FtsQuery
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.TypeFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val library: LibraryRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(TypeFilter.ALL)
    val scope = MutableStateFlow(ALL_SAVED_ID)

    val collections: StateFlow<List<CollectionEntity>> =
        library.liveCollections().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The current search, or null when the text has nothing searchable. Typing is debounced by 200 ms (spec 9.4). */
    val source: StateFlow<MediaSource.Search?> =
        combine(query.debounce(200), filter, scope) { text, type, inScope ->
            FtsQuery.from(text)?.let { MediaSource.Search(it, type, inScope) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val results: Flow<PagingData<MediaEntity>> =
        source.flatMapLatest { search -> if (search == null) flowOf(PagingData.empty()) else library.pager(search) }
            .cachedIn(viewModelScope)
}
