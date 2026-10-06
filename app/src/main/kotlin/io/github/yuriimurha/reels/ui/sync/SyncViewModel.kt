package io.github.yuriimurha.reels.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncViewModel(
    private val controller: SyncController,
    private val library: LibraryRepository,
    private val pacer: Pacer,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    val run: StateFlow<SyncRunEntity?> = controller.latestRun.stateIn(viewModelScope, sharing, null)
    val lastSyncAt: StateFlow<Long?> = controller.lastSyncAt.stateIn(viewModelScope, sharing, null)
    val lastFullSyncAt: StateFlow<Long?> = controller.lastFullSyncAt.stateIn(viewModelScope, sharing, null)

    /** Budgets and cooldown, refreshed every second while the screen is visible. Local reads only. */
    val pacerStatus: StateFlow<PacerStatus?> = flow {
        while (true) {
            emit(pacer.status())
            delay(1_000)
        }
    }.stateIn(viewModelScope, sharing, null)

    val ui: StateFlow<SyncUiState> = combine(run, pacerStatus) { r, p -> syncUiState(r, p, now()) }
        .stateIn(viewModelScope, sharing, syncUiState(null, null, now()))

    fun start(mode: SyncMode) {
        viewModelScope.launch { controller.start(mode) }
    }

    fun cancel() {
        viewModelScope.launch { controller.cancel() }
    }

    fun discard() {
        viewModelScope.launch { controller.discardResumable() }
    }

    fun deleteLibrary() {
        if (run.value?.status == SyncStatus.RUNNING) return
        viewModelScope.launch { library.deleteLibrary() }
    }
}
