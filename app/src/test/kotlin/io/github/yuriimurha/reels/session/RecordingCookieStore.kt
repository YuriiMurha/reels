package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.InMemoryCookieStore

/** An [InMemoryCookieStore] that remembers every write, in order, so tests can assert on cookie attributes. */
class RecordingCookieStore(private val inner: InMemoryCookieStore = InMemoryCookieStore()) : CookieStore by inner {
    /** "set <Set-Cookie value>", "flush" and "clear", oldest first. */
    val events = mutableListOf<String>()

    val setCookies: List<String> get() = events.filter { it.startsWith("set ") }.map { it.removePrefix("set ") }

    val flushes: Int get() = events.count { it == "flush" }

    override fun setCookie(url: String, setCookie: String) {
        events += "set $setCookie"
        inner.setCookie(url, setCookie)
    }

    override fun flush() {
        events += "flush"
        inner.flush()
    }

    override fun clearAll() {
        events += "clear"
        inner.clearAll()
    }
}
