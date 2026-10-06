package io.github.yuriimurha.reels.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.session.userMessage
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
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

    val sessionState: StateFlow<SessionState> = session.state.stateIn(viewModelScope, sharing, SessionState.LoggedOut)

    private val mutableSessionMessage = MutableStateFlow<String?>(null)
    val sessionMessage: StateFlow<String?> = mutableSessionMessage

    private val mutablePasteError = MutableStateFlow<String?>(null)
    val pasteError: StateFlow<String?> = mutablePasteError

    fun checkSession() {
        viewModelScope.launch {
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
