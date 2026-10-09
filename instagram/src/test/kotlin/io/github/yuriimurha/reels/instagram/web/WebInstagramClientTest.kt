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
import kotlin.test.assertTrue

class WebInstagramClientTest {
    private val server = MockWebServer()
    private val cookies = InMemoryCookieStore()

    /** The doc id store in memory: answers [stored] (digits, as every transport needs), and a learned id replaces it. */
    private class FakeDocIds(var stored: String = "555") : DocIdStore {
        val learned = mutableListOf<Pair<GraphQlQuery, String>>()

        override suspend fun docId(query: GraphQlQuery): String = stored

        override suspend fun learned(query: GraphQlQuery, docId: String) {
            learned += query to docId
            stored = docId
        }
    }

    private val docIds = FakeDocIds()
    private var repairs = 0

    /** What the fake repair answers; by default no repair is expected. */
    private var repaired: () -> RepairedQuery = { error("no repair expected") }

    private val repair = QueryRepair { query ->
        repairs++
        assertEquals(WebGraphQl.SAVED_COLLECTIONS, query)
        repaired()
    }

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
        WebInstagramClient({ OkHttpTransport(http(), server.url("/")) }, cookies, docIds, repair)

    private fun fixture(name: String): String = javaClass.getResource("/fixtures/web/$name")!!.readText()

    private fun serve(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun serveFixture(name: String) = serve(fixture(name))

    private fun reply(body: String) = RawReply(200, "application/json", body)

    private val staleBody = """{"errors":[{"message":"x","severity":"CRITICAL"}],"data":null}"""

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
    fun collectionsUsesTheStoredDocId() = runTest {
        serveFixture("collections_graphql.json")
        val page = client().collections(null)
        assertEquals(listOf("Alpha", "Beta"), page.items.map { it.name })
        assertEquals(listOf("17900000000000002", "17900000000000003"), page.items.map { it.id })
        assertEquals(listOf("3100000000000000001", null), page.items.map { it.coverMediaPk })
        assertNull(page.nextCursor)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/" + WebGraphQl.PATH, request.url.encodedPath)
        val form = request.formFields()
        assertEquals("555", form[WebGraphQl.Field.DOC_ID])
        assertEquals(WebGraphQl.SAVED_COLLECTIONS.friendlyName, form[WebGraphQl.Field.FRIENDLY_NAME])
        assertEquals(WebGraphQl.savedCollectionsVariables(null), form[WebGraphQl.Field.VARIABLES])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aLaterPageSendsItsCursorInTheVariables() = runTest {
        serveFixture("collections_graphql.json")
        client().collections("c1")
        assertEquals(WebGraphQl.savedCollectionsVariables("c1"), server.takeRequest().formFields()[WebGraphQl.Field.VARIABLES])
    }

    /**
     * Fact CURSOR is unverified: a server that ignores `after` answers page 1 again, with the same end_cursor. That must stop the
     * walk after one wasted request, not repeat the same POST until the run budget refuses (every run).
     */
    @Test
    fun aCursorThatDoesNotAdvanceIsAShapeChange() = runTest {
        val sameCursor = """{"data":{"viewer":{"collections_unified_with_auto_collections":{"edges":[],""" +
            """"page_info":{"has_next_page":true,"end_cursor":"c1"}}}}}"""
        serve(sameCursor)
        serve(sameCursor)
        val client = client()
        val first = client.collections(null)
        assertEquals("c1", first.nextCursor)
        val error = assertFailsWith<InstagramException.ShapeChanged> { client.collections(first.nextCursor) }
        assertEquals("page_info.end_cursor", error.fieldPath)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aCursorThatAdvancesIsFollowed() = runTest {
        serve(
            """{"data":{"viewer":{"collections_unified_with_auto_collections":{"edges":[],""" +
                """"page_info":{"has_next_page":true,"end_cursor":"c2"}}}}}""",
        )
        assertEquals("c2", client().collections("c1").nextCursor)
    }

    @Test
    fun anInBandRateLimitIsRateLimitedNeverAStaleQuery() = runTest {
        serve(
            """{"data":{"viewer":{"collections_unified_with_auto_collections":null}},""" +
                """"errors":[{"message":"Please wait a few minutes before you try again."}]}""",
        )
        assertFailsWith<InstagramException.RateLimited> { client().collections(null) }
        assertEquals(0, repairs)
    }

    @Test
    fun aStaleReplyThrowsStaleQueryAndNeverRepairsByItself() = runTest {
        serve(staleBody)
        server.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", "text/html").body("<html>not here</html>").build())
        val client = client()
        repeat(2) {
            val error = assertFailsWith<InstagramException.StaleQuery> { client.collections(null) }
            assertEquals(WebGraphQl.SAVED_COLLECTIONS.friendlyName, error.query)
        }
        // Sync decides whether to repair (Task 5): the client never does on its own.
        assertEquals(0, repairs)
        assertEquals(emptyList(), docIds.learned)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theCollectionsReplyKeepsEveryOtherFailure() = runTest {
        serve("""{"status":"fail"}""", code = 429)
        serve("""{"require_login":true}""")
        serve("{}", code = 503)
        serve("""{"data":{"viewer":{}}}""")
        val client = client()
        assertFailsWith<InstagramException.RateLimited> { client.collections(null) }
        assertFailsWith<InstagramException.LoginRequired> { client.collections(null) }
        assertFailsWith<InstagramException.Transient> { client.collections(null) }
        val shape = assertFailsWith<InstagramException.ShapeChanged> { client.collections(null) }
        assertEquals("data.viewer.collections_unified_with_auto_collections", shape.fieldPath)
        assertEquals(0, repairs)
    }

    @Test
    fun repairCollectionsLearnsTheIdAndParsesTheSitesReply() = runTest {
        var built = 0
        repaired = { RepairedQuery("777", reply("for (;;);" + fixture("collections_graphql.json"))) }
        val client = client { built++; HttpClientFactory.create(cookies, "test-agent") }

        val page = client.repairCollections()

        assertEquals(listOf("Alpha", "Beta"), page.items.map { it.name })
        assertEquals(listOf(WebGraphQl.SAVED_COLLECTIONS to "777"), docIds.learned)
        assertEquals(1, repairs)
        // The site's own page sent the query: the app sends nothing, and builds no transport for it.
        assertEquals(0, server.requestCount)
        assertEquals(0, built)
    }

    @Test
    fun theNextPageAfterARepairSendsTheLearnedId() = runTest {
        val more = """{"data":{"viewer":{"collections_unified_with_auto_collections":{"edges":[],""" +
            """"page_info":{"has_next_page":true,"end_cursor":"c1"}}}}}"""
        repaired = { RepairedQuery("777", reply(more)) }
        val client = client()
        val first = client.repairCollections()
        assertEquals("c1", first.nextCursor)

        serveFixture("collections_graphql.json")
        client.collections(first.nextCursor)
        val form = server.takeRequest().formFields()
        assertEquals("777", form[WebGraphQl.Field.DOC_ID])
        assertEquals(WebGraphQl.savedCollectionsVariables("c1"), form[WebGraphQl.Field.VARIABLES])
    }

    @Test
    fun aRepairWhoseReplyIsStaleIsAFailure() = runTest {
        repaired = { RepairedQuery("777", reply(staleBody)) }
        assertFailsWith<InstagramException.StaleQuery> { client().repairCollections() }
        assertEquals(emptyList(), docIds.learned)
        assertEquals("555", docIds.stored)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRepairWhoseReplyFailsOtherwiseLearnsNothingEither() = runTest {
        val client = client()
        repaired = { RepairedQuery("777", reply("""{"require_login":true}""")) }
        assertFailsWith<InstagramException.LoginRequired> { client.repairCollections() }
        repaired = { RepairedQuery("777", reply("""{"data":{"viewer":{"collections_unified_with_auto_collections":{"edges":[]}}}}""")) }
        assertEquals("page_info", assertFailsWith<InstagramException.ShapeChanged> { client.repairCollections() }.fieldPath)
        assertEquals(emptyList(), docIds.learned)
    }

    @Test
    fun aRepairThatCannotRunThrowsItsOwnReasonAndLearnsNothing() = runTest {
        repaired = { throw InstagramException.RepairSkipped("limit") }
        assertFailsWith<InstagramException.RepairSkipped> { client().repairCollections() }
        repaired = { throw InstagramException.RepairFailed("no query") }
        assertFailsWith<InstagramException.RepairFailed> { client().repairCollections() }
        assertEquals(emptyList(), docIds.learned)
        assertEquals(0, server.requestCount)
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
    fun mediaInfoStillStopsOnARedirect() = runTest {
        // A 3xx is never followed and never read: it is a challenge (ChallengeRequired), not "gone" (null).
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/challenge/x/").build())
        assertFailsWith<InstagramException.ChallengeRequired> { client().mediaInfo("1") }
        assertEquals(1, server.requestCount)
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
        serve("""{"form_data":{"username":"user_1"},"status":"ok"}""")
        assertEquals(Account("42", "user_1"), client().currentUser())
        assertEquals("/api/v1/accounts/edit/web_form_data/", server.takeRequest().url.encodedPath)
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
        serve("""{"form_data":{"username":"user_1"},"status":"ok"}""")
        serveFixture("collections_graphql.json")
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

        serveFixture("collections_graphql.json")
        serveFixture("collections_graphql.json")
        client.collections(null)
        client.collections(null)
        // The provider runs once, on the first call, and the client is reused.
        assertEquals(1, built)
        assertEquals(2, server.requestCount)
    }

    /** Spike Q2 answered yes on 2026-10-09: every saved item lists its collections, so sync walks All Saved only (strategy A). */
    @Test
    fun reportsSavedCollectionIdsIsTrue() {
        assertTrue(WebInstagramClient.SAVED_COLLECTION_IDS_CONFIRMED)
        assertTrue(client().reportsSavedCollectionIds)
    }

    @Test
    fun reportsSavedCollectionIdsFollowsTheConstructorArgument() {
        val off = WebInstagramClient(
            { OkHttpTransport(HttpClientFactory.create(cookies, "test-agent"), server.url("/")) },
            cookies,
            docIds,
            repair,
            reportsSavedCollectionIds = false,
        )
        assertFalse(off.reportsSavedCollectionIds)
    }

    @Test
    fun aConnectFailureIsTransient() = runTest {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.close()
        val unreachable =
            WebInstagramClient({ OkHttpTransport(HttpClientFactory.create(cookies, "test-agent"), url) }, cookies, docIds, repair)
        assertFailsWith<InstagramException.Transient> { unreachable.savedMedia(null, null) }
        assertFailsWith<InstagramException.Transient> { unreachable.mediaInfo("3100000000000000001") }
        assertFailsWith<InstagramException.Transient> { unreachable.collections(null) }
    }
}
