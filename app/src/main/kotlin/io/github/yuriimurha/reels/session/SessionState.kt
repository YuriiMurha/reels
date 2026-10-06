package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.data.settings.StoredSession

sealed interface SessionState {
    val handle: String?

    data object LoggedOut : SessionState {
        override val handle: String? = null
    }

    data class Valid(override val handle: String) : SessionState

    data class Expired(override val handle: String?) : SessionState

    /** [challengeUrl] is where Instagram wants verification. Kept in app-private settings, never logged. */
    data class Challenge(val challengeUrl: String?, override val handle: String?) : SessionState
}

fun SessionState.toStored(): StoredSession = when (this) {
    SessionState.LoggedOut -> StoredSession("LOGGED_OUT", null, null)
    is SessionState.Valid -> StoredSession("VALID", handle, null)
    is SessionState.Expired -> StoredSession("EXPIRED", handle, null)
    is SessionState.Challenge -> StoredSession("CHALLENGE", handle, challengeUrl)
}

fun StoredSession.toState(): SessionState = when (kind) {
    "VALID" -> handle?.let { SessionState.Valid(it) } ?: SessionState.Expired(null)
    "EXPIRED" -> SessionState.Expired(handle)
    "CHALLENGE" -> SessionState.Challenge(challengeUrl, handle)
    else -> SessionState.LoggedOut
}
