package io.github.yuriimurha.reels.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuriimurha.reels.session.LoginSession
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.session.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the login screen may ask Instagram, by [purpose] (see [LoginPurpose]). [now] times the csrftoken wait.
 */
class LoginViewModel(
    private val session: LoginSession,
    private val purpose: LoginPurpose = LoginPurpose.LOGIN,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    sealed interface Status {
        data object Waiting : Status
        data object Checking : Status
        data class Done(val state: SessionState) : Status
        data class Failed(val message: String) : Status

        /** [LoginPurpose.CSRF] only: the csrftoken arrived (or the wait ran out); the screen can close. */
        data object CsrfReady : Status
    }

    private val mutableStatus = MutableStateFlow<Status>(Status.Waiting)
    val status: StateFlow<Status> = mutableStatus

    private val openedAt = now()

    /**
     * The fingerprint of the last session sent for validation; never the sessionid itself. A challenge screen starts
     * with the session it was opened for: Instagram just told us what that session needs.
     */
    private var lastChecked: String? = if (purpose == LoginPurpose.CHALLENGE) session.currentSessionFingerprint() else null

    /**
     * Called every second by the screen. Reading cookies is local and free; validating is an Instagram
     * request, so each session is checked at most once until the owner asks to [retry].
     */
    fun onCookiesMaybeReady() {
        if (purpose == LoginPurpose.CSRF) {
            pollCsrfToken()
            return
        }
        if (mutableStatus.value is Status.Checking || !session.hasSessionCookies()) return
        val fingerprint = session.currentSessionFingerprint() ?: return
        if (fingerprint == lastChecked) return
        lastChecked = fingerprint
        mutableStatus.value = Status.Checking
        viewModelScope.launch {
            mutableStatus.value = try {
                Status.Done(session.validate())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Status.Failed(e.userMessage("Couldn't check the session"))
            }
        }
    }

    /** A local read of the jar; no request. After [CSRF_WAIT_MS] the screen closes anyway, quietly. */
    private fun pollCsrfToken() {
        if (mutableStatus.value == Status.CsrfReady) return
        if (session.hasCsrfToken() || now() - openedAt >= CSRF_WAIT_MS) mutableStatus.value = Status.CsrfReady
    }

    /** The owner finished something in the WebView (a challenge, say) and wants the same session checked again. */
    fun retry() {
        if (mutableStatus.value is Status.Checking) return // a check is already out: don't send the session twice
        lastChecked = null
        mutableStatus.value = Status.Waiting
    }

    private companion object {
        const val CSRF_WAIT_MS = 30_000L
    }
}
