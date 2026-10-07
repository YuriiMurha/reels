package io.github.yuriimurha.reels.session

import android.webkit.CookieManager
import android.webkit.WebStorage
import io.github.yuriimurha.reels.instagram.web.CookieStore

/**
 * The WebView's cookie jar: login and API calls share it (spec 4.3). The only place the session is stored.
 * [clearWebStorage] is a hook so unit tests can see logout clear what the WebView kept besides cookies.
 */
class AndroidCookieStore(
    private val clearWebStorage: () -> Unit = { WebStorage.getInstance().deleteAllData() },
) : CookieStore {
    private val manager: CookieManager get() = CookieManager.getInstance()

    override fun cookieHeader(url: String): String? = manager.getCookie(url)

    override fun setCookie(url: String, setCookie: String) = manager.setCookie(url, setCookie)

    override fun flush() = manager.flush()

    /** Logout: cookies, plus localStorage and friends, which can hold Instagram state that outlives the cookies. */
    override fun clearAll() {
        manager.removeAllCookies(null)
        manager.flush()
        clearWebStorage()
    }
}
