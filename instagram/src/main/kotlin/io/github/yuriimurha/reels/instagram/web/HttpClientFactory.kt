package io.github.yuriimurha.reels.instagram.web

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

private const val REDACTED = "\u2588\u2588"

/** Instagram's session headers (ig-set-authorization, x-ig-set-www-claim, ...); redactHeader only takes exact names. */
private val IG_SET_HEADER = Regex("^((?:x-)?ig-set-[^:]*):.*", RegexOption.IGNORE_CASE)

private fun redactIgSetHeaders(message: String): String =
    message.lineSequence().joinToString("\n") { line -> IG_SET_HEADER.replace(line) { "${it.groupValues[1]}: $REDACTED" } }

object HttpClientFactory {
    /**
     * The client for Instagram API calls. Redirects are not followed, so a bounce to /challenge/ or
     * /accounts/login reaches ErrorClassifier. Pass [logger] only in debug builds; secrets are redacted (spec 4.4):
     * cookies, the CSRF token, Instagram's ig-set-* session headers and Location (challenge URLs carry a nonce).
     */
    fun create(cookies: CookieStore, userAgent: String, logger: ((String) -> Unit)? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(CookieStoreJar(cookies))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(WebHeaders.interceptor(userAgent, cookies))
        if (logger != null) {
            builder.addNetworkInterceptor(
                HttpLoggingInterceptor { message -> logger(redactIgSetHeaders(message)) }.apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                    redactHeader("X-CSRFToken")
                    redactHeader("Location")
                },
            )
        }
        return builder.build()
    }
}
