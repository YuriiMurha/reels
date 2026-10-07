package io.github.yuriimurha.reels.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.di.MockModeSwitch
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.session.userMessage
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What the Sync screen does with the answer to a paste. */
sealed interface PasteOutcome {
    /** Instagram accepted the pasted session and it replaced the previous login. */
    data object Accepted : PasteOutcome

    /** Nothing changed; [message] says why, in words that never include an account handle. */
    data class Rejected(val message: String) : PasteOutcome
}

internal const val NOT_A_SESSIONID = "That doesn't look like a sessionid"
internal const val PASTE_REJECTED = "Instagram rejected that session; your current login is unchanged"
internal const val PASTE_FAILED = "Couldn't check that session"

/**
 * Maps [SessionRepository.pasteSessionId]'s answer. Only Valid means the paste was committed: Expired and Challenge
 * mean Instagram rejected it and the previous login was put back, and the handle they carry may be that previous
 * account's, so it is never shown.
 */
internal fun pasteOutcome(result: SessionState?): PasteOutcome = when (result) {
    is SessionState.Valid -> PasteOutcome.Accepted
    null -> PasteOutcome.Rejected(NOT_A_SESSIONID)
    is SessionState.Expired, is SessionState.Challenge -> PasteOutcome.Rejected(PASTE_REJECTED)
    SessionState.LoggedOut -> PasteOutcome.Rejected(PASTE_FAILED) // pasteSessionId never answers this
}

class SyncViewModel(
    private val controller: SyncController,
    private val library: LibraryRepository,
    private val pacer: Pacer,
    private val session: SessionRepository,
    /** The Developer section's Mock mode switch (debug builds); null offers none. */
    private val mockSwitch: MockModeSwitch? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the Mock mode switch works: it waits for WorkManager and writes a file. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
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

    /** The mode this process runs in (true: the fake library), or null when there is no switch. */
    val mockMode: Boolean? = mockSwitch?.usesFake

    /** The latest run once it has been READ: [run] alone can't tell "no run" from "not loaded yet" (both are null). */
    private class LoadedRun(val run: SyncRunEntity?)

    /**
     * `replayExpirationMillis = 0`: once nothing has collected it for 5 s the cached value is dropped (back to null), so
     * a stopped screen can't replay a "loaded" run that went stale while no one was watching, and the switch stays off
     * until the run has been read again.
     */
    private val loadedRun: StateFlow<LoadedRun?> = controller.latestRun.map(::LoadedRun)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

    /**
     * Off until the latest run has loaded, and while it is RUNNING. A restart then could leave WorkManager holding a run
     * of this library for a process that runs the other one (R67), and a run the screen hasn't seen yet may be one. Like
     * [loadedRun] it forgets its value once the screen has been gone for the grace period, so a returning screen never
     * shows the old "enabled" before the run has been read again.
     */
    val mockSwitchEnabled: StateFlow<Boolean> = loadedRun.map { it != null && it.run?.status != SyncStatus.RUNNING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), false)

    private var mockChange: Job? = null

    /** Changes the mode and restarts the app. Refused while the run is loading or RUNNING, and while a change is under way. */
    fun setMockMode(useFake: Boolean) {
        val switch = mockSwitch ?: return
        val loaded = loadedRun.value ?: return
        if (loaded.run?.status == SyncStatus.RUNNING) return
        if (mockChange?.isActive == true) return
        mockChange = viewModelScope.launch(io) { switch.change(useFake) }
    }

    /** The stored session state; null until it has been read (the screen offers no session button before that). */
    val sessionState: StateFlow<SessionState?> = session.state.stateIn(viewModelScope, sharing, null)

    private val mutableSessionMessage = MutableStateFlow<String?>(null)
    val sessionMessage: StateFlow<String?> = mutableSessionMessage

    private val mutablePasteError = MutableStateFlow<String?>(null)
    val pasteError: StateFlow<String?> = mutablePasteError

    private var checkJob: Job? = null

    /** A check is an Instagram request, so a tap while one is being made is ignored (a double tap is one request). */
    fun checkSession() {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            mutableSessionMessage.value = null
            try {
                session.validate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableSessionMessage.value = e.userMessage("Couldn't check the session")
            }
        }
    }

    fun logout() {
        viewModelScope.launch { session.logout() }
    }

    private var pasteJob: Job? = null

    /**
     * [onAccepted] gets whether the WebView still needs to fetch a csrftoken (spec 9.6). A paste is an Instagram
     * request, so a second tap while one is being checked is ignored.
     */
    fun paste(input: String, onAccepted: (needsCsrf: Boolean) -> Unit) {
        if (pasteJob?.isActive == true) return
        pasteJob = viewModelScope.launch {
            val outcome = try {
                pasteOutcome(session.pasteSessionId(input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PasteOutcome.Rejected(e.userMessage(PASTE_FAILED))
            }
            when (outcome) {
                PasteOutcome.Accepted -> {
                    mutablePasteError.value = null
                    onAccepted(!session.hasCsrfToken())
                }
                is PasteOutcome.Rejected -> mutablePasteError.value = outcome.message
            }
        }
    }

    fun clearPasteError() {
        mutablePasteError.value = null
    }
}
