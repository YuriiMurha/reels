package io.github.yuriimurha.reels.ui.lab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.lab.AdapterLab
import io.github.yuriimurha.reels.instagram.lab.LabCall
import io.github.yuriimurha.reels.instagram.lab.LabResult
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.session.userMessage
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.Pacer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

/** What the lab asks of [AdapterLab]; an interface so the ViewModel's tests need no HTTP. */
interface LabRunner {
    suspend fun run(call: LabCall, arg: String?): LabResult
}

/** The real runner: a thin pass-through to the `:instagram` lab, which makes exactly one request per call. */
class AdapterLabRunner(private val lab: AdapterLab) : LabRunner {
    override suspend fun run(call: LabCall, arg: String?): LabResult = lab.run(call, arg)
}

/** The button label and message prefix of each call. */
internal fun LabCall.label(): String = when (this) {
    LabCall.CURRENT_USER -> "Who am I"
    LabCall.COLLECTIONS -> "Collections"
    LabCall.SAVED_ALL -> "All Saved (page 1)"
    LabCall.SAVED_COLLECTION -> "First collection (page 1)"
    LabCall.MEDIA_INFO -> "Media info (first saved item)"
}

/**
 * The latest answer as the screen shows it. [shape] is the lab's redacted shape; [savedPath] is where the scrubbed copy
 * was written, or null when there was none to write (or writing failed). Never the ids, the body or any URL.
 */
data class LabShown(
    val call: LabCall,
    val httpCode: Int,
    val classification: String,
    val shape: String,
    val savedPath: String?,
)

/**
 * Everything the lab screen renders. The ids that chain the calls are not here (only whether they are known): they live
 * in the ViewModel's fields. [message] already names the call it is about.
 */
data class LabUiState(
    val sessionValid: Boolean = false,
    /** The call whose request is out (or waiting for the Pacer), or null. */
    val running: LabCall? = null,
    val hasCollectionId: Boolean = false,
    val hasMediaPk: Boolean = false,
    val shown: LabShown? = null,
    val message: String? = null,
) {
    /** A button is enabled only for a valid session, with no call in flight, and (for the chained two) once its id is known. */
    fun canRun(call: LabCall): Boolean = sessionValid && running == null && when (call) {
        LabCall.SAVED_COLLECTION -> hasCollectionId
        LabCall.MEDIA_INFO -> hasMediaPk
        else -> true
    }
}

/**
 * Debug-only Adapter lab (spec 6.3). Each [tap] is exactly one request on the Pacer's interactive lane, so it obeys the
 * same cooldown, budget and gap as every other request. [labDir] receives the scrubbed JSON and nothing else. [io] is
 * where files are written.
 */
class AdapterLabViewModel(
    private val lab: LabRunner,
    private val pacer: Pacer,
    private val sessionState: Flow<SessionState>,
    private val signals: SessionSignals,
    private val labDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val local = MutableStateFlow(LabUiState())

    val ui: StateFlow<LabUiState> = combine(local, sessionState) { state, session ->
        state.copy(sessionValid = session is SessionState.Valid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabUiState())

    // Real ids, to chain the next call. Fields only: never in the state, a file, a log or a message.
    private var collectionId: String? = null
    private var mediaPk: String? = null

    /**
     * Sends one call. Main thread only. A tap while a call is in flight, or on a chained call whose id is not known yet,
     * is ignored, so a double tap is one request.
     */
    fun tap(call: LabCall) {
        if (local.value.running != null) return
        val arg = when (call) {
            LabCall.SAVED_COLLECTION -> collectionId ?: return
            LabCall.MEDIA_INFO -> mediaPk ?: return
            else -> null
        }
        local.update { it.copy(running = call, message = null) }
        viewModelScope.launch {
            try {
                execute(call, arg)
            } finally {
                local.update { it.copy(running = null) }
            }
        }
    }

    private suspend fun execute(call: LabCall, arg: String?) {
        if (sessionState.first() !is SessionState.Valid) return
        var answer: LabResult? = null
        var failure: Exception? = null
        try {
            pacer.interactive {
                val result = lab.run(call, arg)
                // Keep the answer first: the Pacer arms the cooldown only when the block THROWS RateLimited, and the
                // screen must still get the shape of that 429.
                answer = result
                val error = result.error
                if (error is InstagramException.RateLimited) throw error
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A PacerRefusal (nothing was sent), a Transient or LoginRequired thrown by the lab, a RateLimited that was
            // thrown on purpose above (the answer is already kept), or something unexpected: shown, never rethrown.
            failure = e
        }
        val result = answer
        val problem: Exception? = result?.error ?: failure

        // An answer with a scrubbed copy replaces the call's file; one without removes it, so an export can never return a
        // stale copy of an older answer. No answer at all (a refusal, a network error) leaves the file as it was.
        var saveFailed = false
        val path = result?.let {
            try {
                val json = it.scrubbedJson
                if (json != null) {
                    write(call, json)
                } else {
                    discard(call)
                    null
                }
            } catch (e: IOException) {
                saveFailed = true
                null
            }
        }
        if (result != null) {
            when (call) {
                LabCall.COLLECTIONS -> result.ids.firstCollectionId?.let { collectionId = it }
                LabCall.SAVED_ALL -> result.ids.firstMediaPk?.let { mediaPk = it }
                else -> Unit
            }
        }
        val text = listOfNotNull(problem?.userMessage(CALL_FAILED), SAVE_FAILED.takeIf { saveFailed }).joinToString(". ")
        local.update { state ->
            state.copy(
                hasCollectionId = collectionId != null,
                hasMediaPk = mediaPk != null,
                shown = result?.let { LabShown(call, it.httpCode, it.classification, it.shape, path) } ?: state.shown,
                message = text.takeIf { it.isNotEmpty() }?.let { "${call.label()}: $it" },
            )
        }

        // Last, so the answer is on screen even if the state write fails. The URL a challenge carries goes to the session
        // only; it is never put in the state.
        try {
            when (problem) {
                is InstagramException.ChallengeRequired -> signals.challengeRequired(problem.challengeUrl)
                is InstagramException.LoginRequired -> signals.loginRequired()
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed write of the session state must not crash a debug screen; the owner can still tap Check now.
        }
    }

    /** Overwrites `<call name lowercased>.json` with the scrubbed JSON, the only thing the lab ever writes. */
    private suspend fun write(call: LabCall, json: String): String = withContext(io) {
        labDir.mkdirs()
        val file = fileOf(call)
        file.writeText(json)
        file.path
    }

    /** Removes this call's own file, if any (never another call's). Throws [IOException] when it cannot be removed. */
    private suspend fun discard(call: LabCall) {
        withContext(io) { Files.deleteIfExists(fileOf(call).toPath()) }
    }

    private fun fileOf(call: LabCall) = File(labDir, call.name.lowercase() + ".json")

    private companion object {
        const val CALL_FAILED = "The call failed"
        const val SAVE_FAILED = "Couldn't save the scrubbed copy"
    }
}
