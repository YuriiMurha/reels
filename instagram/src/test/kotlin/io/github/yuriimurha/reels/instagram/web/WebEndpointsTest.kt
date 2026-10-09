package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import java.io.File
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

    /** The hidden page's landing rule (the transport asks this before every call). */
    @Test
    fun aPagesPathSaysWhetherTheOwnerMustLogInOrVerify() {
        for (path in listOf("/accounts/login/", "/accounts/login", "/accounts/login/two_factor/")) {
            assertEquals(WebEndpoints.Landing.LOGIN, WebEndpoints.landingOf(path), path)
        }
        for (path in listOf("/challenge/", "/challenge/action/AXabc/", "/accounts/suspended/")) {
            assertEquals(WebEndpoints.Landing.CHALLENGE, WebEndpoints.landingOf(path), path)
        }
        for (path in listOf("/", "", "/explore/", "/accounts/onetap/", "/accounts/edit/", "/api/v1/accounts/login/", "/reel/challenge/")) {
            assertNull(WebEndpoints.landingOf(path), path)
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
    }

    /**
     * Spec 2026-10-09 §1: the api/v1 collections list is not served on the web (a 404 page). The names come from the website's
     * GraphQL query ([WebGraphQl.SAVED_COLLECTIONS]); nothing in the adapter may build that old URL again.
     */
    @Test
    fun noAdapterCodeAsksForTheApiV1CollectionsList() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "unit tests must run from the instagram module directory")
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 20, "the scan found ${sources.size} files")
        val offenders = sources.filter { "collections/list" in it.readText() }.map { it.name }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun theLoginCheckIsTheEditFormEndpoint() {
        val url = WebEndpoints.currentUser()
        assertEquals("/api/v1/accounts/edit/web_form_data/", url.encodedPath)
        assertNull(url.encodedQuery)
        assertEquals("https://www.instagram.com/api/v1/accounts/edit/web_form_data/", url.toString())
    }

    @Test
    fun relativeIsTheEncodedPathAndQueryWithoutALeadingSlash() {
        val b = WebEndpoints.BASE
        assertEquals("api/v1/accounts/edit/web_form_data/", WebEndpoints.relative(WebEndpoints.currentUser()))
        assertEquals("api/v1/feed/saved/posts/", WebEndpoints.relative(WebEndpoints.savedPosts(b, null)))
        assertEquals("api/v1/feed/saved/posts/?max_id=a%2Bb%2Fc%3D", WebEndpoints.relative(WebEndpoints.savedPosts(b, "a+b/c=")))
        // Whatever the encoding, resolving the relative form against the base gives the same URL back.
        val page = WebEndpoints.collectionPosts(b, "17900000000000002", "c 1[\"x\"]")
        assertEquals(page, b.resolve(WebEndpoints.relative(page)))
        assertTrue(WebEndpoints.relative(page).startsWith("api/v1/feed/collection/17900000000000002/posts/?max_id="))
    }

    /** P6: one host per client, so OkHttp never coalesces connections and never re-sends after a 421. */
    @Test
    fun everyApiEndpointStaysOnTheBaseHost() {
        val b = WebEndpoints.BASE
        listOf(
            WebEndpoints.currentUser(), WebEndpoints.savedPosts(b, "x"),
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
        assertNull(WebEndpoints.collectionPosts(b, "1", null).queryParameter("max_id"))
        assertEquals("a+b/c=", WebEndpoints.collectionPosts(b, "1", "a+b/c=").queryParameter("max_id"))
    }

    @Test
    fun theIdsAreRefusedAtTheirLengthBoundaries() {
        val max = "9".repeat(30)
        assertEquals("/api/v1/media/$max/info/", WebEndpoints.mediaInfo(WebEndpoints.BASE, max).encodedPath)
        assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.mediaInfo(WebEndpoints.BASE, "9".repeat(31)) }
        assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.collectionPosts(WebEndpoints.BASE, "1 ", null) }
    }
}
