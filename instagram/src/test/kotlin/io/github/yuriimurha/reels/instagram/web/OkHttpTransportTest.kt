package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The OkHttp implementation of the transport: one request per call, every status a reply, redirects never followed. */
class OkHttpTransportTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun transport() = OkHttpTransport(HttpClientFactory.create(InMemoryCookieStore(), "UA"), server.url("/"))

    private suspend fun get(path: String = "api/v1/x/") = transport().get(path)

    @Test
    fun theRelativePathAndQueryAreSentToTheBase() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        transport().get("api/v1/feed/saved/posts/?max_id=a%2Bb")
        val url = server.takeRequest().url
        assertEquals("/api/v1/feed/saved/posts/", url.encodedPath)
        assertEquals("a+b", url.queryParameter("max_id"))
    }

    @Test
    fun aPathThatLeavesTheBaseHostIsRefusedBeforeAnyRequest() = runTest {
        for (bad in listOf("https://evil.example/x", "//evil.example/x", "http://127.0.0.1:1/x")) {
            assertFailsWith<IllegalArgumentException>(bad) { transport().get(bad) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anyStatusIsAReplyWithItsContentTypeAndBodyAndNoThrow() = runTest {
        server.enqueue(MockResponse.Builder().code(404).addHeader("Content-Type", "application/json").body("""{"message":"gone"}""").build())
        server.enqueue(MockResponse.Builder().code(503).body("down").build())

        val notFound = get()
        assertEquals(404, notFound.code)
        assertEquals("application/json", notFound.contentType)
        assertEquals("""{"message":"gone"}""", notFound.body)
        assertFalse(notFound.redirected)

        assertEquals(503, get().code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aRedirectIsNotFollowedAndItsBodyIsNotRead() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/challenge/x/").body("secret-zq6").build())
        val reply = get()
        assertEquals(302, reply.code)
        assertTrue(reply.redirected)
        assertNull(reply.body)
        assertFalse("secret-zq6" in reply.toString())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRedirectToTheLoginPageBecomesTheRequireLoginBodyInsteadOfARedirect() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://www.instagram.com/accounts/login/?next=/").build())
        val reply = get()
        assertEquals(302, reply.code)
        assertFalse(reply.redirected)
        assertEquals("""{"require_login":true}""", reply.body)
        assertIs<InstagramException.LoginRequired>(classifyReply(reply))
    }

    @Test
    fun aConnectFailureIsTransient() = runTest {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.close()
        val unreachable = OkHttpTransport(HttpClientFactory.create(InMemoryCookieStore(), "UA"), url)
        assertFailsWith<InstagramException.Transient> { unreachable.get("api/v1/x/") }
    }

    @Test
    fun aBodyThatCannotBeReadIsANullBodyOnAnyStatus() = runTest {
        server.enqueue(cutResponse(200))
        server.enqueue(cutResponse(503))
        server.enqueue(cutResponse(429))
        server.enqueue(cutResponse(403))
        for (code in listOf(200, 503, 429, 403)) {
            val reply = get()
            assertEquals(code, reply.code)
            assertNull(reply.body, "$code")
            assertFalse(reply.redirected)
        }
        assertEquals(4, server.requestCount)
    }

    @Test
    fun anUnreadableBodyIsClassifiedFromItsStatusAlone() = runTest {
        server.enqueue(cutResponse(200))
        server.enqueue(cutResponse(503))
        server.enqueue(cutResponse(429))
        server.enqueue(cutResponse(403))
        server.enqueue(cutResponse(400))
        assertIs<InstagramException.Transient>(classifyReply(get()))
        assertIs<InstagramException.Transient>(classifyReply(get()))
        assertIs<InstagramException.RateLimited>(classifyReply(get()))
        assertIs<InstagramException.LoginRequired>(classifyReply(get()))
        assertEquals("http.400.unreadable", assertIs<InstagramException.ShapeChanged>(assertNotNull(classifyReply(get()))).fieldPath)
    }

    @Test
    fun aReplyNeverPrintsItsBody() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("secret-zq7").build())
        val reply = get()
        assertEquals("secret-zq7", reply.body)
        assertEquals("RawReply(code=200, redirected=false, body=<10 chars>)", reply.toString())
    }
}
