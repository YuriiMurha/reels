package io.github.yuriimurha.reels.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import kotlinx.coroutines.flow.Flow

class GridViewModel(source: MediaSource, library: LibraryRepository) : ViewModel() {
    val items: Flow<PagingData<MediaEntity>> = library.pager(source).cachedIn(viewModelScope)
}
