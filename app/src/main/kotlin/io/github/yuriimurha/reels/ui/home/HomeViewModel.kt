package io.github.yuriimurha.reels.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.ui.common.SyncStatusSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(library: LibraryRepository, sync: SyncController) : ViewModel() {
    /** Null until the first emission, so the screen doesn't flash the empty state. */
    val cards: StateFlow<List<CollectionCard>?> =
        library.collectionCards().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val status: StateFlow<SyncStatusSummary> =
        combine(sync.latestRun, sync.lastSyncAt) { run, last -> SyncStatusSummary.from(run, last) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatusSummary.Never)
}
