package io.github.yuriimurha.reels.instagram.web

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CookieStoreJarTest {
    private val url = "https://www.instagram.com/api/v1/x/".toHttpUrl()

    @Test
    fun loadsEveryWellFormedCookie() {
        val store = InMemoryCookieStore().apply {
            setCookie(url.toString(), "a=1")
            setCookie(url.toString(), "b=2")
        }
        assertEquals(listOf("a" to "1", "b" to "2"), CookieStoreJar(store).loadForRequest(url).map { it.name to it.value })
    }

    @Test
    fun savesUpdatesAndFlushes() {
        val store = InMemoryCookieStore()
        val cookie = Cookie.Builder().name("rur").value("r1").domain("www.instagram.com").build()
        CookieStoreJar(store).saveFromResponse(url, listOf(cookie))
        assertEquals("r1", store.cookieValue(url.toString(), "rur"))
        assertEquals(1, store.flushes)
    }

    @Test
    fun cookieValueFindsOneCookie() {
        val store = InMemoryCookieStore().apply {
            setCookie(url.toString(), "sessionid=s1")
            setCookie(url.toString(), "ds_user_id=42")
        }
        assertEquals("42", store.cookieValue(url.toString(), "ds_user_id"))
        assertNull(store.cookieValue(url.toString(), "csrftoken"))
    }

    @Test
    fun expiredSetCookieRemovesTheCookie() {
        val store = InMemoryCookieStore().apply { setCookie(url.toString(), "a=1") }
        store.setCookie(url.toString(), "a=; Max-Age=0")
        assertNull(store.cookieHeader(url.toString()))
    }
}
