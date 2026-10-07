package io.github.yuriimurha.reels.instagram.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Every Instagram URL and host the rest of the app needs. Nothing outside `:instagram` spells one out. */
object WebEndpoints {
    /** Instagram's home page. A challenge without a usable URL redirects from here to the checkpoint, and loading it gives the WebView a csrftoken. */
    const val HOME_URL = "https://www.instagram.com/"

    /** Instagram's own login page, where the login WebView starts. */
    const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

    val BASE: HttpUrl = HOME_URL.toHttpUrl()

    /**
     * The login flow may only visit https pages on Instagram's own domains and on Facebook and Meta, which its login and
     * verification use.
     */
    val LOGIN_DOMAINS: List<String> = listOf("instagram.com", "facebook.com", "meta.com")

    /**
     * True for an https page on one of [LOGIN_DOMAINS] or a subdomain of it. The match has a dot boundary, so
     * `evilinstagram.com` and `instagram.com.evil.example` are out; scheme and host are compared without regard to case.
     */
    fun isLoginPage(scheme: String?, host: String?): Boolean {
        if (!scheme.equals("https", ignoreCase = true) || host == null) return false
        val lower = host.lowercase()
        return LOGIN_DOMAINS.any { lower == it || lower.endsWith(".$it") }
    }

    /** Candidate from spec 6.2, confirmed on the phone in Task 18. */
    fun currentUser(base: HttpUrl, userId: String): HttpUrl =
        base.newBuilder()
            .addPathSegments("api/v1/users")
            .addPathSegment(userId)
            .addPathSegment("info")
            .addPathSegment("")
            .build()
}
