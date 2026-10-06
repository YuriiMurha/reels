package io.github.yuriimurha.reels.instagram.web

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

object HttpClientFactory {
    /**
     * The client for Instagram API calls. Redirects are not followed, so a bounce to /challenge/ or
     * /accounts/login reaches ErrorClassifier. Pass [logger] only in debug builds; secrets are redacted (spec 4.4).
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
                HttpLoggingInterceptor { message -> logger(message) }.apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                    redactHeader("X-CSRFToken")
                },
            )
        }
        return builder.build()
    }
}
