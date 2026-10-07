package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.MediaType
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebInstagramClientTest {
    private val server = MockWebServer()
    private val cookies = InMemoryCookieStore()

    @BeforeTest
    fun start() {
        server.start()
        cookies.setCookie("https://www.instagram.com", "ds_user_id=42")
        // Built from parts at runtime: a fake value, but the shape of a real session cookie.
        cookies.setCookie("https://www.instagram.com", "session" + "id=s1")
    }

    @AfterTest
    fun stop() = server.close()

    private fun client(http: () -> OkHttpClient = { HttpClientFactory.create(cookies, "test-agent") }) =
        WebInstagramClient(http, cookies, base = server.url("/"))

    private fun fixture(name: String): String = javaClass.getResource("/fixtures/web/$name")!!.readText()

    private fun serve(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun serveFixture(name: String) = serve(fixture(name))

    @Test
    fun walksSavedPagesWithMaxId() = runTest {
        serveFixture("saved_page_more.json")
        serveFixture("saved_page_last.json")
        val client = client()

        val first = client.savedMedia(null, null)
        assertEquals(listOf(MediaType.REEL, MediaType.IMAGE, MediaType.CAROUSEL), first.items.map { it.type })
        assertEquals("QVFE_cursor_2", first.nextCursor)
        val firstRequest = server.takeRequest().url
        assertEquals("/api/v1/feed/saved/posts/", firstRequest.encodedPath)
        assertNull(firstRequest.queryParameter("max_id"))

        val second = client.savedMedia(null, first.nextCursor)
        assertEquals(1, second.items.size)
        assertNull(second.nextCursor)
        val secondRequest = server.takeRequest().url
        assertEquals("/api/v1/feed/saved/posts/", secondRequest.encodedPath)
        assertEquals("QVFE_cursor_2", secondRequest.queryParameter("max_id"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun collectionPostsHitsTheCollectionPath() = runTest {
        serveFixture("collection_page.json")
        val page = client().savedMedia("17900000000000002", null)
        assertEquals(2, page.items.size)
        assertNull(page.nextCursor)
        assertEquals("/api/v1/feed/collection/17900000000000002/posts/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun collectionPostsSendsTheCursorAsMaxId() = runTest {
        serveFixture("collection_page.json")
        client().savedMedia("17900000000000002", "QVFE_cursor_9")
        val url = server.takeRequest().url
        assertEquals("/api/v1/feed/collection/17900000000000002/posts/", url.encodedPath)
        assertEquals("QVFE_cursor_9", url.queryParameter("max_id"))
    }

    @Test
    fun collectionsFiltersToMediaCollections() = runTest {
        serveFixture("collections_list.json")
        val page = client().collections(null)
        assertEquals(2, page.items.size)
        assertEquals(listOf("Food", "Travel"), page.items.map { it.name })
        assertNull(page.nextCursor)
        val url = server.takeRequest().url
        assertEquals("/api/v1/collections/list/", url.encodedPath)
        assertEquals(
            "[\"ALL_MEDIA_AUTO_COLLECTION\",\"MEDIA\",\"AUDIO_AUTO_COLLECTION\"]",
            url.queryParameter("collection_types"),
        )
    }

    @Test
    fun mediaInfoReturnsTheItem() = runTest {
        serveFixture("media_info.json")
        val media = assertNotNull(client().mediaInfo("3100000000000000001"))
        assertEquals("3100000000000000001", media.pk)
        assertEquals(MediaType.REEL, media.type)
        assertEquals("/api/v1/media/3100000000000000001/info/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun mediaInfoReturnsNullWhenNotFound() = runTest {
        val body = """{"message":"Media not found or unavailable","status":"fail"}"""
        serve(body, code = 404)
        serve(body, code = 400)
        serve("""{"items":[],"status":"ok"}""")
        val client = client()
        assertNull(client.mediaInfo("3100000000000000001"))
        assertNull(client.mediaInfo("3100000000000000001"))
        assertNull(client.mediaInfo("3100000000000000001"))
        // One request per call, no retry of a not-found.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun mediaInfoStillStopsOnAChallenge() = runTest {
        serve("""{"message":"challenge_required","challenge":{"url":"/challenge/x/"}}""", code = 400)
        assertFailsWith<InstagramException.ChallengeRequired> { client().mediaInfo("3100000000000000001") }
    }

    @Test
    fun mediaInfoStillStopsOnLoginAndRateLimit() = runTest {
        serve("""{"message":"login_required","status":"fail"}""", code = 400)
        serve("""{"message":"Please wait a few minutes before you try again.","status":"fail"}""", code = 400)
        serve("""{"status":"fail"}""", code = 429)
        val client = client()
        assertFailsWith<InstagramException.LoginRequired> { client.mediaInfo("3100000000000000001") }
        assertFailsWith<InstagramException.RateLimited> { client.mediaInfo("3100000000000000001") }
        assertFailsWith<InstagramException.RateLimited> { client.mediaInfo("3100000000000000001") }
    }

    @Test
    fun mediaInfoOnlyTreatsHttp400And404AsGone() = runTest {
        serve("""{"status":"fail"}""", code = 410)
        val error = assertFailsWith<InstagramException.ShapeChanged> { client().mediaInfo("3100000000000000001") }
        assertEquals("http.410", error.fieldPath)
        serve("{}", code = 500)
        assertFailsWith<InstagramException.Transient> { client().mediaInfo("3100000000000000001") }
    }

    @Test
    fun savedMediaDoesNotTurnANotFoundIntoAnEmptyPage() = runTest {
        serve("""{"message":"Not found","status":"fail"}""", code = 404)
        val error = assertFailsWith<InstagramException.ShapeChanged> { client().savedMedia(null, null) }
        assertEquals("http.404", error.fieldPath)
    }

    @Test
    fun idsThatAreNotDigitsAreRefusedBeforeAnyRequest() = runTest {
        val client = client()
        assertFailsWith<InstagramException.ShapeChanged> { client.savedMedia("../x", null) }
        assertFailsWith<InstagramException.ShapeChanged> { client.savedMedia("1/2", "c") }
        assertFailsWith<InstagramException.ShapeChanged> { client.mediaInfo("1/2") }
        assertFailsWith<InstagramException.ShapeChanged> { client.mediaInfo("") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun currentUserDelegatesToTheProbe() = runTest {
        serve("""{"user":{"pk":42,"username":"user_1"},"status":"ok"}""")
        assertEquals(Account("42", "user_1"), client().currentUser())
        assertEquals("/api/v1/users/42/info/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun currentUserWithoutACookieIsLoggedOutWithoutARequest() = runTest {
        cookies.clearAll()
        assertFailsWith<InstagramException.LoginRequired> { client().currentUser() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnreadable400IsAShapeChangeNotANotFound() = runTest {
        // Headers say 400 but the body is cut off: a challenge or rate limit could hide in it, so mediaInfo must not
        // answer "not found" (null). The unreadable marker keeps it out of the http.400 / http.404 not-found rule.
        server.enqueue(cutResponse(400))
        val error = assertFailsWith<InstagramException.ShapeChanged> { client().mediaInfo("3100000000000000001") }
        assertEquals("http.400.unreadable", error.fieldPath)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anInvalidMediaPkNeverBuildsTheClient() = runTest {
        var built = 0
        val client = client { built++; HttpClientFactory.create(cookies, "test-agent") }
        assertFailsWith<InstagramException.ShapeChanged> { client.mediaInfo("1/2") }
        assertFailsWith<InstagramException.ShapeChanged> { client.mediaInfo("") }
        assertEquals(0, built)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun currentUserAndAListCallShareOneHttpClient() = runTest {
        var built = 0
        val client = client { built++; HttpClientFactory.create(cookies, "test-agent") }
        serve("""{"user":{"pk":42,"username":"user_1"},"status":"ok"}""")
        serveFixture("collections_list.json")
        client.currentUser()
        client.collections(null)
        assertEquals(1, built)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun noRequestUntilFirstCall() = runTest {
        var built = 0
        val client = client { built++; HttpClientFactory.create(cookies, "test-agent") }
        assertEquals(0, built)
        assertEquals(0, server.requestCount)

        serveFixture("collections_list.json")
        serveFixture("collections_list.json")
        client.collections(null)
        client.collections(null)
        // The provider runs once, on the first call, and the client is reused.
        assertEquals(1, built)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun reportsSavedCollectionIdsDefaultsToFalse() {
        assertFalse(WebInstagramClient.SAVED_COLLECTION_IDS_CONFIRMED)
        assertFalse(client().reportsSavedCollectionIds)
    }

    @Test
    fun reportsSavedCollectionIdsFollowsTheConstructorArgument() {
        val on = WebInstagramClient({ HttpClientFactory.create(cookies, "test-agent") }, cookies, reportsSavedCollectionIds = true, base = server.url("/"))
        assertEquals(true, on.reportsSavedCollectionIds)
    }

    @Test
    fun aConnectFailureIsTransient() = runTest {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.close()
        val unreachable = WebInstagramClient({ HttpClientFactory.create(cookies, "test-agent") }, cookies, base = url)
        assertFailsWith<InstagramException.Transient> { unreachable.savedMedia(null, null) }
        assertFailsWith<InstagramException.Transient> { unreachable.mediaInfo("3100000000000000001") }
    }
}
