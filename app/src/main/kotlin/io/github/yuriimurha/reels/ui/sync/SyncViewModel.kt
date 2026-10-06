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
import kotlinx.coroutines.flow.map
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

    /** The Pacer's status and the moment it was read. */
    private data class Tick(val status: PacerStatus, val at: Long)

    /**
     * Budgets and cooldown, refreshed every second while the screen is visible. Local reads only. The time travels
     * with the status because during a cooldown the status itself never changes, and a StateFlow drops repeats.
     */
    private val tick: StateFlow<Tick?> = flow {
        while (true) {
            emit(Tick(pacer.status(), now()))
            delay(1_000)
        }
    }.stateIn(viewModelScope, sharing, null)

    val pacerStatus: StateFlow<PacerStatus?> = tick.map { it?.status }.stateIn(viewModelScope, sharing, null)

    val ui: StateFlow<SyncUiState> = combine(run, tick) { r, t -> syncUiState(r, t?.status, t?.at ?: now()) }
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
