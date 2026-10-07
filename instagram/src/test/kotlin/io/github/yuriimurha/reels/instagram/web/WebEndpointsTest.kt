package io.github.yuriimurha.reels.instagram.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebEndpointsTest {
    @Test
    fun thePagesTheLoginFlowOpens() {
        assertEquals("https://www.instagram.com/accounts/login/", WebEndpoints.LOGIN_URL)
        assertEquals("https://www.instagram.com/", WebEndpoints.HOME_URL)
    }

    @Test
    fun theApiBaseIsTheHomePage() {
        assertEquals(WebEndpoints.HOME_URL, WebEndpoints.BASE.toString())
    }

    @Test
    fun theLoginHostAllowlistIsInstagramFacebookAndMeta() {
        assertEquals(listOf("instagram.com", "facebook.com", "meta.com"), WebEndpoints.LOGIN_DOMAINS)
    }

    @Test
    fun anHttpsPageOnADomainOrASubdomainIsAllowed() {
        for (host in listOf("instagram.com", "www.instagram.com", "l.instagram.com", "m.facebook.com", "facebook.com", "www.meta.com")) {
            assertTrue(WebEndpoints.isLoginPage("https", host), host)
        }
    }

    @Test
    fun theSchemeAndHostAreMatchedWithoutRegardToCase() {
        assertTrue(WebEndpoints.isLoginPage("HTTPS", "WWW.Instagram.COM"))
    }

    @Test
    fun theHostHasADotBoundary() {
        for (host in listOf("evilinstagram.com", "instagram.com.evil.example", "instagram.com.", "notfacebook.com", "metaa.com", "facebook.com.evil.example")) {
            assertFalse(WebEndpoints.isLoginPage("https", host), host)
        }
    }

    @Test
    fun onlyHttpsWithAHostIsAllowed() {
        assertFalse(WebEndpoints.isLoginPage("http", "www.instagram.com"))
        assertFalse(WebEndpoints.isLoginPage("intent", "instagram.com"))
        assertFalse(WebEndpoints.isLoginPage(null, "www.instagram.com"))
        assertFalse(WebEndpoints.isLoginPage("https", null))
    }
}
