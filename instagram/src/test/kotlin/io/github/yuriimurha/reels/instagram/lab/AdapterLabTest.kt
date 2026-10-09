package io.github.yuriimurha.reels.instagram.lab

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.DocIdStore
import io.github.yuriimurha.reels.instagram.web.GraphQlQuery
import io.github.yuriimurha.reels.instagram.web.HttpClientFactory
import io.github.yuriimurha.reels.instagram.web.InMemoryCookieStore
import io.github.yuriimurha.reels.instagram.web.OkHttpTransport
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.instagram.web.cutResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdapterLabTest {
    private val server = MockWebServer()
    private val cookies = InMemoryCookieStore()

    // Real-looking ids, but fixture ones: they must never reach a result's text.
    private val mediaPk = "3100000000000000001"
    private val collectionId = "17900000000000002"

    @BeforeTest
    fun start() {
        server.start()
        // The cookie name is built from parts, like every secret-looking string in the tests.
        cookies.setCookie("https://www.instagram.com", "ds_user" + "_id=42")
    }

    @AfterTest
    fun stop() = server.close()

    /** The stored doc id the lab must send: digits, as every transport needs, and not the built-in one. */
    private val docIds = object : DocIdStore {
        override suspend fun docId(query: GraphQlQuery): String = "555"

        override suspend fun learned(query: GraphQlQuery, docId: String) = error("the lab never learns an id")
    }

    private fun lab(http: () -> OkHttpClient = { HttpClientFactory.create(cookies, "test-agent") }) =
        AdapterLab({ OkHttpTransport(http(), server.url("/")) }, cookies, docIds)

    private fun fixture(name: String): String = javaClass.getResource("/fixtures/web/$name")!!.readText()

    private fun serve(body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build())

    private fun serveFixture(name: String) = serve(fixture(name))

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    /** The urlencoded form of a GraphQL POST, decoded. */
    private fun form(request: RecordedRequest): Map<String, String> =
        request.body!!.utf8().split('&').associate { field ->
            val (name, value) = field.split('=', limit = 2)
            URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    @Test
    fun eachCallHitsExactlyOneExpectedPath() = runTest {
        val lab = lab()
        serve("""{"form_data":{"username":"user_1"},"status":"ok"}""")
        serveFixture("collections_graphql.json")
        serveFixture("saved_page_more.json")
        serveFixture("collection_page.json")
        serveFixture("media_info.json")

        lab.run(LabCall.CURRENT_USER, null)
        lab.run(LabCall.COLLECTIONS, null)
        lab.run(LabCall.SAVED_ALL, null)
        lab.run(LabCall.SAVED_COLLECTION, collectionId)
        lab.run(LabCall.MEDIA_INFO, mediaPk)

        val requests = (1..5).map { server.takeRequest() }
        val paths = requests.map { it.url }
        assertEquals(
            listOf(
                "/api/v1/accounts/edit/web_form_data/",
                "/api/graphql",
                "/api/v1/feed/saved/posts/",
                "/api/v1/feed/collection/$collectionId/posts/",
                "/api/v1/media/$mediaPk/info/",
            ),
            paths.map { it.encodedPath },
        )
        assertEquals(listOf("GET", "POST", "GET", "GET", "GET"), requests.map { it.method })
        // The first page of each walk only: a lab call never carries a cursor.
        assertTrue(paths.all { it.queryParameter("max_id") == null })
        assertEquals(WebGraphQl.savedCollectionsVariables(null), form(requests[1])[WebGraphQl.Field.VARIABLES])
        assertEquals(5, server.requestCount)
    }

    /** R1: the Collections call is one POST of the website's own query, with the doc id the store holds. */
    @Test
    fun collectionsSendsOnePostOfTheSitesQueryWithTheStoredDocId() = runTest {
        serveFixture("collections_graphql.json")
        val result = lab().run(LabCall.COLLECTIONS, null)
        assertEquals("ok", result.classification)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/" + WebGraphQl.PATH, request.url.encodedPath)
        val form = form(request)
        assertEquals("555", form[WebGraphQl.Field.DOC_ID])
        assertEquals(WebGraphQl.SAVED_COLLECTIONS.friendlyName, form[WebGraphQl.Field.FRIENDLY_NAME])
        assertEquals(WebGraphQl.savedCollectionsVariables(null), form[WebGraphQl.Field.VARIABLES])
        assertEquals(1, server.requestCount)
    }

    /** R1: a reply behind the website's `for (;;);` guard still shows its shape, a scrubbed copy and the first user collection. */
    @Test
    fun collectionsShowsTheShapeAfterTheGuardAndReadsTheFirstUserCollection() = runTest {
        serve("for (;;);" + fixture("collections_graphql.json"), headers = mapOf("Content-Type" to "text/javascript; charset=utf-8"))
        val result = lab().run(LabCall.COLLECTIONS, null)
        assertEquals("ok", result.classification)
        assertNull(result.error)
        assertTrue(result.shape.startsWith("data object\n  viewer object\n    collections_unified_with_auto_collections object"), result.shape)
        assertTrue("        node object" in result.shape, result.shape)
        assertTrue("      has_next_page boolean = false" in result.shape, result.shape)
        assertFalse("Alpha" in result.shape || "Alpha" in assertNotNull(result.scrubbedJson), "a collection name is data")
        assertEquals(3, parse(result.scrubbedJson!!).jsonObject["data"]!!.jsonObject["viewer"]!!.jsonObject
            ["collections_unified_with_auto_collections"]!!.jsonObject["edges"]!!.jsonArray.size)
        // ALL_MEDIA_AUTO_COLLECTION comes first: the first user collection is Alpha.
        assertEquals(collectionId, result.ids.firstCollectionId)
    }

    @Test
    fun aStaleCollectionsReplyIsAnAnswerClassifiedStaleQuery() = runTest {
        serve("""{"errors":[{"message":"x","severity":"CRITICAL"}],"data":null}""")
        serve("<html>not here</html>", code = 404, headers = mapOf("Content-Type" to "text/html"))
        val lab = lab()
        for (expectedShape in listOf("errors array[1]", "(not JSON: text/html, 21 chars)")) {
            val result = lab.run(LabCall.COLLECTIONS, null)
            assertEquals("StaleQuery", result.classification)
            assertIs<InstagramException.StaleQuery>(result.error)
            assertTrue(result.shape.startsWith(expectedShape), result.shape)
            assertNull(result.ids.firstCollectionId)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun savedAllFillsTheFirstMediaPk() = runTest {
        serveFixture("saved_page_more.json")
        val result = lab().run(LabCall.SAVED_ALL, null)
        assertEquals(mediaPk, result.ids.firstMediaPk)
        assertNull(result.ids.firstCollectionId)
        assertEquals("ok", result.classification)
        assertNull(result.error)
        assertEquals(200, result.httpCode)
        assertEquals(LabCall.SAVED_ALL, result.call)
    }

    @Test
    fun collectionsFillsTheFirstUserCollectionIdSkippingTheAutomaticOnes() = runTest {
        serveFixture("collections_graphql.json")
        val result = lab().run(LabCall.COLLECTIONS, null)
        // The first node is ALL_MEDIA_AUTO_COLLECTION (fact AUTO): the first user collection is Alpha.
        assertEquals(collectionId, result.ids.firstCollectionId)
        assertNull(result.ids.firstMediaPk)
    }

    @Test
    fun aCollectionPageAndAMediaInfoFillTheirFirstPk() = runTest {
        serveFixture("collection_page.json")
        serveFixture("media_info.json")
        val lab = lab()
        assertEquals("3100000000000000005", lab.run(LabCall.SAVED_COLLECTION, collectionId).ids.firstMediaPk)
        assertEquals(mediaPk, lab.run(LabCall.MEDIA_INFO, mediaPk).ids.firstMediaPk)
    }

    @Test
    fun currentUserNeedsTheCookieAndYieldsNoIds() = runTest {
        serve("""{"form_data":{"username":"user_1"},"status":"ok"}""")
        val result = lab().run(LabCall.CURRENT_USER, null)
        assertNull(result.ids.firstCollectionId)
        assertNull(result.ids.firstMediaPk)
        assertTrue("username string(len 6, text)" in result.shape, result.shape)
        assertFalse("42" in result.shape.replace("number(2 digits)", ""), result.shape)
    }

    @Test
    fun aRateLimitIsAnAnswerNotAnException() = runTest {
        serve("""{"message":"Please wait a few minutes before you try again.","status":"fail"}""", code = 429)
        val result = lab().run(LabCall.SAVED_ALL, null)
        assertEquals(429, result.httpCode)
        assertEquals("RateLimited", result.classification)
        assertIs<InstagramException.RateLimited>(result.error)
        assertEquals("message string = \"Please wait a few minutes before you try again.\"\nstatus string = \"fail\"", result.shape)
        val scrubbed = parse(assertNotNull(result.scrubbedJson)).jsonObject
        assertEquals("fail", scrubbed["status"]!!.jsonPrimitive.content)
        assertNull(result.ids.firstMediaPk)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aChallengeBodyIsClassifiedAsAChallenge() = runTest {
        serve("""{"message":"challenge_required","challenge":{"url":"/challenge/x/","lock":true},"status":"fail"}""", code = 400)
        val result = lab().run(LabCall.MEDIA_INFO, mediaPk)
        assertEquals(400, result.httpCode)
        assertEquals("ChallengeRequired", result.classification)
        assertIs<InstagramException.ChallengeRequired>(result.error)
        assertTrue("challenge object" in result.shape, result.shape)
        assertTrue("  lock boolean = true" in result.shape, result.shape)
        // The challenge URL is held by the exception only, never by the shape or the scrubbed copy.
        assertFalse("/challenge/x/" in result.shape)
        assertFalse("/challenge/x/" in assertNotNull(result.scrubbedJson))
        assertFalse("/challenge/x/" in result.toString())
    }

    private fun cut(code: Int, location: String? = null): MockResponse = cutResponse(code, location)

    @Test
    fun aRateLimitWhoseBodyIsCutIsStillRateLimitedSoTheCooldownArms() = runTest {
        server.enqueue(cut(429))
        val result = lab().run(LabCall.SAVED_ALL, null)
        assertEquals(429, result.httpCode)
        assertEquals("RateLimited", result.classification)
        assertIs<InstagramException.RateLimited>(result.error)
        assertEquals("(unreadable body)", result.shape)
        assertNull(result.scrubbedJson)
        assertNull(result.ids.firstMediaPk)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aForbiddenWhoseBodyIsCutIsStillLoginRequired() = runTest {
        server.enqueue(cut(403))
        val result = lab().run(LabCall.COLLECTIONS, null)
        assertEquals(403, result.httpCode)
        assertEquals("LoginRequired", result.classification)
        assertIs<InstagramException.LoginRequired>(result.error)
        assertEquals("(unreadable body)", result.shape)
        assertNull(result.scrubbedJson)
    }

    @Test
    fun aChallengeRedirectIsStillAChallengeButItsTargetIsUnknown() = runTest {
        server.enqueue(cut(302, location = "/challenge/x/"))
        val result = lab().run(LabCall.MEDIA_INFO, mediaPk)
        assertEquals(302, result.httpCode)
        assertEquals("ChallengeRequired", result.classification)
        // A redirect is not followed and its Location is not read, so no URL is kept (the owner opens the WebView).
        assertNull(assertIs<InstagramException.ChallengeRequired>(result.error).challengeUrl)
        assertEquals("(redirect, not followed)", result.shape)
        assertNull(result.scrubbedJson)
    }

    @Test
    fun aPlainClientErrorWhoseBodyIsCutIsAnUnreadableShapeChangeNotANotFound() = runTest {
        server.enqueue(cut(400))
        val result = lab().run(LabCall.MEDIA_INFO, mediaPk)
        assertEquals("ShapeChanged", result.classification)
        assertEquals("http.400.unreadable", assertIs<InstagramException.ShapeChanged>(result.error).fieldPath)
        assertEquals("(unreadable body)", result.shape)
    }

    @Test
    fun otherFailuresAreAnswersToo() = runTest {
        serve("", code = 302, headers = mapOf("Location" to "/accounts/login/"))
        serve("""{"message":"login_required","status":"fail"}""", code = 400)
        serve("""{"message":"Not found","status":"fail"}""", code = 404)
        serve("<html>down</html>", code = 503, headers = mapOf("Content-Type" to "text/html"))
        val lab = lab()

        val redirect = lab.run(LabCall.COLLECTIONS, null)
        assertEquals(302, redirect.httpCode)
        assertEquals("LoginRequired", redirect.classification)
        // The transport turns a bounce to the login page into the require_login body Instagram itself sends.
        assertEquals("require_login boolean = true", redirect.shape)
        assertNotNull(redirect.scrubbedJson)

        assertEquals("LoginRequired", lab.run(LabCall.COLLECTIONS, null).classification)

        val notFound = lab.run(LabCall.MEDIA_INFO, mediaPk)
        assertEquals("ShapeChanged", notFound.classification)
        assertEquals("http.404", assertIs<InstagramException.ShapeChanged>(notFound.error).fieldPath)

        val down = lab.run(LabCall.SAVED_ALL, null)
        assertEquals("Transient", down.classification)
        assertIs<InstagramException.Transient>(down.error)
    }

    @Test
    fun theServerSeesExactlyOneRequestPerRunEvenOnFailures() = runTest {
        val lab = lab()
        serve("""{"status":"fail"}""", code = 429)
        serve("{}", code = 503, headers = mapOf("Retry-After" to "0"))
        serve("", code = 302, headers = mapOf("Location" to "/challenge/"))
        serve("""{"message":"checkpoint_required","status":"fail"}""", code = 400)
        serveFixture("saved_page_more.json")
        listOf(LabCall.SAVED_ALL, LabCall.COLLECTIONS, LabCall.SAVED_ALL, LabCall.COLLECTIONS, LabCall.SAVED_ALL).forEach {
            lab.run(it, null)
        }
        assertEquals(5, server.requestCount)
    }

    @Test
    fun aResultsToStringNeverContainsAnId() = runTest {
        val lab = lab()
        serveFixture("collections_graphql.json")
        serveFixture("saved_page_more.json")
        val collections = lab.run(LabCall.COLLECTIONS, null)
        val saved = lab.run(LabCall.SAVED_ALL, null)

        assertEquals(collectionId, collections.ids.firstCollectionId)
        assertEquals(mediaPk, saved.ids.firstMediaPk)
        for (text in listOf(collections.toString(), saved.toString(), collections.ids.toString(), saved.ids.toString())) {
            assertFalse(collectionId in text, text)
            assertFalse(mediaPk in text, text)
            assertFalse("31000000" in text || "17900000" in text, text)
        }
        // The same holds for what a result shows and saves.
        for (text in listOf(collections.shape, saved.shape, collections.scrubbedJson!!, saved.scrubbedJson!!)) {
            assertFalse(collectionId in text, text)
            assertFalse(mediaPk in text, text)
        }
        assertTrue("<set>" in saved.ids.toString())
    }

    @Test
    fun theScrubbedCopyHoldsNoRawValue() = runTest {
        serveFixture("saved_page_more.json")
        val result = lab().run(LabCall.SAVED_ALL, null)
        val scrubbed = assertNotNull(result.scrubbedJson)
        for (leak in listOf("Synthetic caption", "Cx1aBcDeFg1", "/v/t51/", "QVFE_cursor_2", "7700000001", "1700003000")) {
            assertFalse(leak in scrubbed, "scrubbed copy holds $leak")
            assertFalse(leak in result.shape, "shape holds $leak")
        }
        assertTrue("\n" in scrubbed, "pretty-printed")
        assertTrue("  \"items\"" in scrubbed, scrubbed)
        assertEquals(3, parse(scrubbed).jsonObject["items"]!!.jsonArray.size)
    }

    @Test
    fun oneScrubberServesTheWholeLabSessionSoFixturesCrossReference() = runTest {
        val lab = lab()
        serveFixture("saved_page_more.json")
        serveFixture("media_info.json")
        val savedPk = parse(lab.run(LabCall.SAVED_ALL, null).scrubbedJson!!).jsonObject["items"]!!.jsonArray[0]
            .jsonObject["media"]!!.jsonObject["pk"]!!.jsonPrimitive.content
        val infoPk = parse(lab.run(LabCall.MEDIA_INFO, mediaPk).scrubbedJson!!).jsonObject["items"]!!.jsonArray[0]
            .jsonObject["pk"]!!.jsonPrimitive.content
        assertEquals(savedPk, infoPk)
        assertNotEquals(mediaPk, savedPk)
        assertEquals(mediaPk.length, savedPk.length)
        // And the lab's scrubber is the one anyone else gets to use for the same session.
        val direct = lab.scrubber.scrub(parse("""{"pk":$mediaPk}""")).jsonObject["pk"]!!.jsonPrimitive.content
        assertEquals(savedPk, direct)
    }

    @Test
    fun differentRealIdsStayDifferentAcrossTheCallsOfOneSession() = runTest {
        val lab = lab()
        serveFixture("collection_page.json")
        serveFixture("media_info.json")
        val page = parse(lab.run(LabCall.SAVED_COLLECTION, collectionId).scrubbedJson!!).jsonObject["items"]!!.jsonArray
        val pageFirst = page[0].jsonObject["media"]!!.jsonObject["pk"]!!.jsonPrimitive.content
        val infoFirst = parse(lab.run(LabCall.MEDIA_INFO, mediaPk).scrubbedJson!!).jsonObject["items"]!!.jsonArray[0]
            .jsonObject["pk"]!!.jsonPrimitive.content
        // Real ...05 and ...01 are different ids, so a lab session must not hand both the same stand-in.
        assertNotEquals(pageFirst, infoFirst)
    }

    @Test
    fun idsAreOnlyTakenFromAnAnswerThatClassifiedAsOk() = runTest {
        serve("""{"items":[{"media":{"pk":$mediaPk}}],"status":"fail"}""")
        val result = lab().run(LabCall.SAVED_ALL, null)
        assertEquals("ShapeChanged", result.classification)
        assertNull(result.ids.firstMediaPk)
    }

    @Test
    fun anEmptyBodyIsLabelledAndHasNoScrubbedCopy() = runTest {
        serve("")
        val result = lab().run(LabCall.SAVED_ALL, null)
        assertEquals("(empty body)", result.shape)
        assertNull(result.scrubbedJson)
        assertEquals("ShapeChanged", result.classification)
    }

    @Test
    fun aBodyThatIsNotJsonShowsOnlyItsTypeAndLength() = runTest {
        val body = "<html><body>Log in as secret_handle_77</body></html>"
        serve(body, code = 200, headers = mapOf("Content-Type" to "text/html; charset=utf-8"))
        val result = lab().run(LabCall.COLLECTIONS, null)
        assertEquals("LoginRequired", result.classification)
        assertEquals("(not JSON: text/html, ${body.length} chars)", result.shape)
        assertNull(result.scrubbedJson)
        assertFalse("secret_handle_77" in result.toString())
    }

    @Test
    fun aJsonBodyThatIsNotAnObjectStillGetsAShapeAndAScrubbedCopy() = runTest {
        serve("""["abc","def"]""")
        val result = lab().run(LabCall.SAVED_ALL, null)
        assertEquals("ShapeChanged", result.classification)
        assertEquals("$ array[2]\n  [0] string(len 3, text)", result.shape)
        assertEquals(parse("""["s_1","s_2"]"""), parse(result.scrubbedJson!!))
        assertNull(result.ids.firstMediaPk)
    }

    @Test
    fun noForbiddenWordReachesAShapeOrAScrubbedCopy() = runTest {
        val cdn = "scontent-x." + "cdn" + "instagram.com"
        val userKey = "ds_user" + "_id"
        val sessionKey = "session" + "id"
        serve(
            """{"items":[{"media":{"pk":$mediaPk,"$userKey":"42","${sessionKey}_x":"s1",
            "url":"https://$cdn/v/t51/1.jpg?oe=6720A3F0&$sessionKey=s1","message":"Open $cdn"}}],
            "message":"See $cdn","more_available":false,"status":"ok"}""",
        )
        val result = lab().run(LabCall.SAVED_ALL, null)
        val words = listOf(sessionKey, "csrf" + "token", "Cookie" + ":", userKey, "scontent", "fb" + "cdn", "cdn" + "instagram")
        for (text in listOf(result.shape, result.scrubbedJson!!, result.toString())) {
            words.forEach { assertFalse(text.contains(it, ignoreCase = true), "'$it' in $text") }
        }
        assertFalse(result.shape.startsWith("(withheld"), "the shape was withheld instead of being clean: ${result.shape}")
        // The id was still found for chaining: only the saved text is clean.
        assertEquals(mediaPk, result.ids.firstMediaPk)
    }

    @Test
    fun withoutASessionCookieCurrentUserIsLoggedOutBeforeAnyRequest() = runTest {
        var built = 0
        val lab = lab { built++; HttpClientFactory.create(cookies, "test-agent") }
        cookies.clearAll()
        assertFailsWith<InstagramException.LoginRequired> { lab.run(LabCall.CURRENT_USER, null) }
        cookies.setCookie("https://www.instagram.com", "ds_user" + "_id=4a2")
        assertFailsWith<InstagramException.LoginRequired> { lab.run(LabCall.CURRENT_USER, null) }
        assertEquals(0, server.requestCount)
        assertEquals(0, built)
    }

    @Test
    fun anIdThatIsNotDigitsOrIsMissingIsRefusedBeforeAnyRequestOrClient() = runTest {
        var built = 0
        val lab = lab { built++; HttpClientFactory.create(cookies, "test-agent") }
        assertFailsWith<InstagramException.ShapeChanged> { lab.run(LabCall.SAVED_COLLECTION, "../x") }
        assertFailsWith<InstagramException.ShapeChanged> { lab.run(LabCall.SAVED_COLLECTION, null) }
        assertFailsWith<InstagramException.ShapeChanged> { lab.run(LabCall.MEDIA_INFO, "1/2") }
        assertFailsWith<InstagramException.ShapeChanged> { lab.run(LabCall.MEDIA_INFO, null) }
        assertFailsWith<InstagramException.ShapeChanged> { lab.run(LabCall.MEDIA_INFO, "") }
        assertEquals(0, server.requestCount)
        assertEquals(0, built)
    }

    @Test
    fun theClientIsBuiltOnTheFirstRealCallAndReused() = runTest {
        var built = 0
        val lab = lab { built++; HttpClientFactory.create(cookies, "test-agent") }
        assertEquals(0, built)
        serveFixture("collections_graphql.json")
        serveFixture("saved_page_more.json")
        lab.run(LabCall.COLLECTIONS, null)
        lab.run(LabCall.SAVED_ALL, null)
        assertEquals(1, built)
    }

    @Test
    fun aConnectFailureAndAnUnreadableBodyAreTransient() = runTest {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.close()
        val unreachable = AdapterLab({ OkHttpTransport(HttpClientFactory.create(cookies, "test-agent"), url) }, cookies, docIds)
        assertFailsWith<InstagramException.Transient> { unreachable.run(LabCall.SAVED_ALL, null) }
        assertFailsWith<InstagramException.Transient> { unreachable.run(LabCall.COLLECTIONS, null) }

        server.enqueue(cut(200))
        server.enqueue(cut(200))
        assertFailsWith<InstagramException.Transient> { lab().run(LabCall.SAVED_ALL, null) }
        assertFailsWith<InstagramException.Transient> { lab().run(LabCall.COLLECTIONS, null) }
        assertEquals(2, server.requestCount)
    }
}
