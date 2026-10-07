package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun apiEndpointsMatchTheSpecCandidates() {
        val b = WebEndpoints.BASE
        assertEquals("/api/v1/feed/saved/posts/", WebEndpoints.savedPosts(b, null).encodedPath)
        assertEquals("c2", WebEndpoints.savedPosts(b, "c2").queryParameter("max_id"))
        assertEquals("/api/v1/feed/collection/17900000000000002/posts/", WebEndpoints.collectionPosts(b, "17900000000000002", null).encodedPath)
        assertEquals("/api/v1/media/3100000000000000001/info/", WebEndpoints.mediaInfo(b, "3100000000000000001").encodedPath)
        val list = WebEndpoints.collections(b, null)
        assertEquals("/api/v1/collections/list/", list.encodedPath)
        assertEquals("[\"ALL_MEDIA_AUTO_COLLECTION\",\"MEDIA\",\"AUDIO_AUTO_COLLECTION\"]", list.queryParameter("collection_types"))
    }

    /** P6: one host per client, so OkHttp never coalesces connections and never re-sends after a 421. */
    @Test
    fun everyApiEndpointStaysOnTheBaseHost() {
        val b = WebEndpoints.BASE
        listOf(
            WebEndpoints.currentUser(b, "42"), WebEndpoints.collections(b, "x"), WebEndpoints.savedPosts(b, "x"),
            WebEndpoints.collectionPosts(b, "1", "x"), WebEndpoints.mediaInfo(b, "1"),
        ).forEach { assertEquals("www.instagram.com", it.host); assertEquals("https", it.scheme) }
    }

    @Test
    fun idsThatAreNotDigitsAreRefused() {
        assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.collectionPosts(WebEndpoints.BASE, "../x", null) }
        assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.mediaInfo(WebEndpoints.BASE, "1/2") }
    }

    @Test
    fun aRefusedIdNamesItsField() {
        assertEquals("collection_id", assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.collectionPosts(WebEndpoints.BASE, "..", null) }.fieldPath)
        assertEquals("pk", assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.mediaInfo(WebEndpoints.BASE, "") }.fieldPath)
    }

    @Test
    fun theCursorIsOnlySentWhenThereIsOne() {
        val b = WebEndpoints.BASE
        assertNull(WebEndpoints.savedPosts(b, null).queryParameter("max_id"))
        assertNull(WebEndpoints.collections(b, null).queryParameter("max_id"))
        assertNull(WebEndpoints.collectionPosts(b, "1", null).queryParameter("max_id"))
        assertEquals("a+b/c=", WebEndpoints.collections(b, "a+b/c=").queryParameter("max_id"))
    }

    @Test
    fun theIdsAreRefusedAtTheirLengthBoundaries() {
        val max = "9".repeat(30)
        assertEquals("/api/v1/media/$max/info/", WebEndpoints.mediaInfo(WebEndpoints.BASE, max).encodedPath)
        assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.mediaInfo(WebEndpoints.BASE, "9".repeat(31)) }
        assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.collectionPosts(WebEndpoints.BASE, "1 ", null) }
    }
}
