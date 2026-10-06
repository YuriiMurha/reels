package io.github.yuriimurha.reels.instagram.web

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val REDACTED = "\u2588\u2588"

/** Instagram's session headers (ig-set-authorization, x-ig-set-www-claim, ...); redactHeader only takes exact names. */
private val IG_SET_HEADER = Regex("^((?:x-)?ig-set-[^:]*):.*", RegexOption.IGNORE_CASE)

private fun redactIgSetHeaders(message: String): String =
    message.lineSequence().joinToString("\n") { line -> IG_SET_HEADER.replace(line) { "${it.groupValues[1]}: $REDACTED" } }

/**
 * Outermost application interceptor. An unchecked throwable from the interceptor chain or the cookie bridge would
 * otherwise be rethrown by OkHttp on its dispatcher thread (fatal on Android) and quoted in the failure it reports.
 * Here it leaves as a plain IOException naming only the class: no message, no cause.
 */
private val crashGuard = Interceptor { chain ->
    try {
        chain.proceed(chain.request())
    } catch (e: RuntimeException) {
        throw IOException(e::class.java.simpleName.ifEmpty { "RuntimeException" })
    }
}

object HttpClientFactory {
    /**
     * The client for Instagram API calls. Redirects are not followed, so a bounce to /challenge/ or
     * /accounts/login reaches ErrorClassifier. Pass [logger] only in debug builds; secrets are redacted (spec 4.4):
     * cookies, the CSRF token, Instagram's ig-set-* session headers and Location (challenge URLs carry a nonce).
     * OkHttp's silent retry of a failed connection is off: every request must go through the Pacer.
     */
    fun create(cookies: CookieStore, userAgent: String, logger: ((String) -> Unit)? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cookieJar(CookieStoreJar(cookies))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(crashGuard)
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
