package io.github.yuriimurha.reels.ui.login

import kotlinx.serialization.Serializable

/** Why the login WebView was opened. It decides what the screen may spend an Instagram request on. */
@Serializable
enum class LoginPurpose {
    /** The owner is logging in. Each session that appears, including one already in the jar, is checked once. */
    LOGIN,

    /** Instagram wants verification for the session in the jar. That session is already known: opening costs no request. */
    CHALLENGE,

    /** A pasted session was just validated; the page is open only so the WebView receives a csrftoken. No request at all. */
    CSRF,
}
