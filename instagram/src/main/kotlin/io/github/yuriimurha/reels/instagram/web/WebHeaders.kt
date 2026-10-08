package io.github.yuriimurha.reels.instagram.web

import okhttp3.Interceptor

/** Makes API calls look like the website running in the app's WebView (spec 6.1). */
object WebHeaders {
    /** The X-IG-App-ID the mobile website sends. */
    const val APP_ID = "1217981644879628"

    /** The X-ASBD-ID the website sends with it. */
    const val ASBD_ID = "359341"

    fun interceptor(userAgent: String, cookies: CookieStore) = Interceptor { chain ->
        val request = chain.request()
        val builder = request.newBuilder()
            .header("User-Agent", userAgent)
            .header("X-IG-App-ID", APP_ID)
            .header("X-ASBD-ID", ASBD_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", "https://www.instagram.com/")
        cookies.cookieValue(request.url.toString(), "csrftoken")
            ?.takeIf { it.isHeaderSafe() }
            ?.let { builder.header("X-CSRFToken", it) }
        chain.proceed(builder.build())
    }
}
