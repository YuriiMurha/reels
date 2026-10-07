package io.github.yuriimurha.reels.instagram.web

/** A single-site cookie store for JVM tests. Ignores domains and paths; an empty value or Max-Age=0 deletes. */
class InMemoryCookieStore : CookieStore {
    private val cookies = linkedMapOf<String, String>()

    var flushes = 0
        private set

    override fun cookieHeader(url: String): String? =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }

    override fun setCookie(url: String, setCookie: String) {
        val pair = setCookie.substringBefore(';')
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        val expired = setCookie.split(';').any { it.trim().equals("Max-Age=0", ignoreCase = true) }
        if (expired || value.isEmpty()) cookies.remove(name) else cookies[name] = value
    }

    override fun flush() {
        flushes++
    }

    override fun clearAll() {
        cookies.clear()
    }
}
