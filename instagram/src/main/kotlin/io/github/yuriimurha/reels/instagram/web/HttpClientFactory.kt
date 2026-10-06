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
 * Outermost application interceptor. Anything that is not an IOException (unchecked, or a checked one such as a
 * TimeoutException that Kotlin lets through) from the interceptor chain or the cookie bridge would otherwise be
 * rethrown by OkHttp on its dispatcher thread (fatal on Android) and quoted in the failure it reports. Here it leaves
 * as a plain IOException naming only the class: no message, no cause. An IOException passes through untouched.
 *
 * This chain is not coroutine code, so a CancellationException (an IllegalStateException) showing up in it is also
 * converted; coroutine cancellation goes through Call.cancel(), which surfaces as an IOException, not through here.
 */
private val crashGuard = Interceptor { chain ->
    try {
        chain.proceed(chain.request())
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e::class.java.simpleName.ifEmpty { "Exception" })
    }
}

/**
 * OkHttp re-sends a request by itself after a 503 that says "Retry-After: 0", below the application interceptors, so
 * the Pacer would count one request while two go out. Without the header OkHttp makes no follow-up. Registered before
 * the logging interceptor so the debug log (closer to the wire) still shows what Instagram really sent.
 */
private val noRetryAfterOn503 = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (response.code == 503 && response.header("Retry-After") != null) {
        response.newBuilder().removeHeader("Retry-After").build()
    } else {
        response
    }
}

object HttpClientFactory {
    /**
     * The client for Instagram API calls. Redirects are not followed, so a bounce to /challenge/ or
     * /accounts/login reaches ErrorClassifier. Pass [logger] only in debug builds; secrets are redacted (spec 4.4):
     * cookies, the CSRF token, Instagram's ig-set-* session headers and Location (challenge URLs carry a nonce).
     * OkHttp's silent re-sends (failed connection, 503 with Retry-After: 0) are off: every request must go through the
     * Pacer.
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
            .addNetworkInterceptor(noRetryAfterOn503)
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
