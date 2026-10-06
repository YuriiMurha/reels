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

class LoginViewModel(private val session: LoginSession) : ViewModel() {
    sealed interface Status {
        data object Waiting : Status
        data object Checking : Status
        data class Done(val state: SessionState) : Status
        data class Failed(val message: String) : Status
    }

    private val mutableStatus = MutableStateFlow<Status>(Status.Waiting)
    val status: StateFlow<Status> = mutableStatus

    /** The fingerprint of the last session sent for validation; never the sessionid itself. */
    private var lastChecked: String? = null

    /**
     * Called every second by the screen. Reading cookies is local and free; validating is an Instagram
     * request, so each session is checked at most once until the owner asks to [retry].
     */
    fun onCookiesMaybeReady() {
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

    /** The owner finished something in the WebView (a challenge, say) and wants the same session checked again. */
    fun retry() {
        lastChecked = null
        mutableStatus.value = Status.Waiting
    }
}
