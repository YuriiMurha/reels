package io.github.yuriimurha.reels.instagram.web

import kotlin.test.Test
import kotlin.test.assertEquals

/** The exact Set-Cookie values the app writes into the shared jar for a pasted session (spec D3) and for its rollback. */
class WebSessionCookiesTest {
    @Test
    fun theSessionCookieIsHttpOnlyAndLivesAYear() {
        assertEquals(
            "sessionid=42%3Aab; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=31536000",
            WebSessionCookies.sessionCookie("42%3Aab"),
        )
    }

    @Test
    fun aNullSessionCookieExpiresIt() {
        assertEquals(
            "sessionid=; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=0",
            WebSessionCookies.sessionCookie(null),
        )
    }

    @Test
    fun theUserCookieIsReadableByScriptAndLivesNinetyDays() {
        assertEquals(
            "ds_user_id=42; Domain=.instagram.com; Path=/; Secure; Max-Age=7776000",
            WebSessionCookies.userCookie("42"),
        )
    }

    @Test
    fun aNullUserCookieExpiresIt() {
        assertEquals(
            "ds_user_id=; Domain=.instagram.com; Path=/; Secure; Max-Age=0",
            WebSessionCookies.userCookie(null),
        )
    }

    @Test
    fun theCookiesAreWrittenForInstagramsOrigin() {
        assertEquals("https://www.instagram.com", WebSessionCookies.ORIGIN)
    }

    @Test
    fun aWrittenCookieRoundTripsThroughTheStoreAndAnExpiredOneRemovesIt() {
        val store = InMemoryCookieStore()
        store.setCookie(WebSessionCookies.ORIGIN, WebSessionCookies.sessionCookie("s1"))
        store.setCookie(WebSessionCookies.ORIGIN, WebSessionCookies.userCookie("41"))
        assertEquals("s1", store.cookieValue(WebSessionCookies.ORIGIN, "sessionid"))
        assertEquals("41", store.cookieValue(WebSessionCookies.ORIGIN, "ds_user_id"))
        store.setCookie(WebSessionCookies.ORIGIN, WebSessionCookies.sessionCookie(null))
        store.setCookie(WebSessionCookies.ORIGIN, WebSessionCookies.userCookie(null))
        assertEquals(null, store.cookieValue(WebSessionCookies.ORIGIN, "sessionid"))
        assertEquals(null, store.cookieValue(WebSessionCookies.ORIGIN, "ds_user_id"))
    }
}
