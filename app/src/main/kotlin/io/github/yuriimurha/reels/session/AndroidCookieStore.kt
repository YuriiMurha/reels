package io.github.yuriimurha.reels.session

import android.webkit.CookieManager
import io.github.yuriimurha.reels.instagram.web.CookieStore

/** The WebView's cookie jar: login and API calls share it (spec 4.3). The only place the session is stored. */
class AndroidCookieStore : CookieStore {
    private val manager: CookieManager get() = CookieManager.getInstance()

    override fun cookieHeader(url: String): String? = manager.getCookie(url)

    override fun setCookie(url: String, setCookie: String) = manager.setCookie(url, setCookie)

    override fun flush() = manager.flush()

    override fun clearAll() {
        manager.removeAllCookies(null)
        manager.flush()
    }
}
