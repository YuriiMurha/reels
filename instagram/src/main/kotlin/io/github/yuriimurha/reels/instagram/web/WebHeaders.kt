package io.github.yuriimurha.reels.instagram.web

import okhttp3.Interceptor

/** Makes API calls look like the website running in the app's WebView (spec 6.1). */
object WebHeaders {
    /** The X-IG-App-ID the website sends. Checked on the phone in Task 18 and again in the M3 spike. */
    const val APP_ID = "936619743392459"

    fun interceptor(userAgent: String, cookies: CookieStore) = Interceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
            .header("User-Agent", userAgent)
            .header("X-IG-App-ID", APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", "https://www.instagram.com/")
        cookies.cookieValue(request.url.toString(), "csrftoken")
            ?.takeIf { it.isHeaderSafe() }
            ?.let { builder.header("X-CSRFToken", it) }
        chain.proceed(builder.build())
    }
}
