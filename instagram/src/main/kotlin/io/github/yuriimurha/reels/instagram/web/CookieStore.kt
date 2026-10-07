package io.github.yuriimurha.reels.instagram.web

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Where Instagram's cookies live. On Android it's the WebView's CookieManager, so login and API calls share one jar. */
interface CookieStore {
    /** Cookies for [url] as a request header value ("a=1; b=2"), or null. */
    fun cookieHeader(url: String): String?

    /** Stores one Set-Cookie header value received from [url]. */
    fun setCookie(url: String, setCookie: String)

    fun flush()

    /** Logout: forgets every cookie. */
    fun clearAll()
}

fun CookieStore.cookieValue(url: String, name: String): String? =
    cookieHeader(url)
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotEmpty() }

/**
 * True when every character is printable ASCII without spaces (0x21..0x7E): safe to put in a Cookie or X-CSRFToken
 * header. Anything else would make OkHttp throw an exception whose message can quote the value.
 */
internal fun String.isHeaderSafe(): Boolean = all { it in '\u0021'..'\u007e' }

/** Gives OkHttp the store's cookies and writes Instagram's Set-Cookie updates back into it (spec 4.3). */
class CookieStoreJar(private val store: CookieStore) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = store.cookieHeader(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty() || !name.isHeaderSafe() || !value.isHeaderSafe()) {
                null
            } else {
                runCatching { Cookie.Builder().name(name).value(value).domain(url.host).build() }.getOrNull()
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        cookies.forEach { store.setCookie(url.toString(), it.toString()) }
        store.flush()
    }
}
