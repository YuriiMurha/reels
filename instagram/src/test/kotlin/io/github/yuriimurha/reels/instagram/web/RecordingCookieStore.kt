package io.github.yuriimurha.reels.instagram.web

import java.net.URI

/**
 * A test [CookieStore] that keys cookies by the host of the URL it is given (like the WebView's CookieManager,
 * unlike [InMemoryCookieStore]) and records every lookup and every raw Set-Cookie it is handed.
 */
class RecordingCookieStore : CookieStore {
    private val byHost = linkedMapOf<String, LinkedHashMap<String, String>>()

    /** Every URL [cookieHeader] was asked about. */
    val lookups = mutableListOf<String>()

    /** Every (url, raw Set-Cookie) pair [setCookie] received. */
    val written = mutableListOf<Pair<String, String>>()

    var flushes = 0
        private set

    fun put(host: String, name: String, value: String) {
        byHost.getOrPut(host) { linkedMapOf() }[name] = value
    }

    override fun cookieHeader(url: String): String? {
        lookups += url
        return byHost[URI(url).host]?.entries?.joinToString("; ") { "${it.key}=${it.value}" }?.ifEmpty { null }
    }

    override fun setCookie(url: String, setCookie: String) {
        written += url to setCookie
        val host = URI(url).host
        val pair = setCookie.substringBefore(';')
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        val expired = setCookie.split(';').any { it.trim().equals("max-age=0", ignoreCase = true) }
        if (expired || value.isEmpty()) byHost[host]?.remove(name) else put(host, name, value)
    }

    override fun flush() {
        flushes++
    }

    override fun clearAll() {
        byHost.clear()
    }
}
