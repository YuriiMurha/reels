package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** A response whose headers arrive but whose body is cut off: reading it throws an IOException. */
class WebJsonTest {
    private val server = MockWebServer()
    private val client = HttpClientFactory.create(InMemoryCookieStore(), "UA")

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun cutOff(code: Int, location: String? = null): MockResponse =
        MockResponse.Builder()
            .code(code)
            .apply { location?.let { addHeader("Location", it) } }
            .body("x".repeat(4096))
            .onResponseBody(SocketEffect.CloseSocket())
            .build()

    private suspend fun fetch() = client.getJsonObject(server.url("/api/v1/x/"))

    @Test
    fun aChallengeRedirectStopsEvenWhenItsBodyCannotBeRead() = runTest {
        server.enqueue(cutOff(302, location = "/challenge/"))
        val error = assertFailsWith<InstagramException.ChallengeRequired> { fetch() }
        assertEquals("https://www.instagram.com/challenge/", error.challengeUrl)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRateLimitStopsEvenWhenItsBodyCannotBeRead() = runTest {
        server.enqueue(cutOff(429))
        assertFailsWith<InstagramException.RateLimited> { fetch() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aLoginBounceStopsEvenWhenItsBodyCannotBeRead() = runTest {
        server.enqueue(cutOff(401))
        assertFailsWith<InstagramException.LoginRequired> { fetch() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anUnknownClientErrorNeedsRepairEvenWhenItsBodyCannotBeRead() = runTest {
        server.enqueue(cutOff(404))
        server.enqueue(cutOff(400))
        // Marked unreadable, so a caller that treats http.400 and http.404 as "not found" can't mistake it for one.
        assertEquals("http.404.unreadable", assertFailsWith<InstagramException.ShapeChanged> { fetch() }.fieldPath)
        assertEquals("http.400.unreadable", assertFailsWith<InstagramException.ShapeChanged> { fetch() }.fieldPath)
    }

    @Test
    fun aForbiddenAndAnUnreadableChallengeKeepTheirOwnClassification() = runTest {
        server.enqueue(cutOff(403))
        server.enqueue(cutOff(301, location = "/accounts/login/"))
        assertFailsWith<InstagramException.LoginRequired> { fetch() }
        assertFailsWith<InstagramException.LoginRequired> { fetch() }
    }

    @Test
    fun aSuccessOrServerErrorWithAnUnreadableBodyIsTransient() = runTest {
        server.enqueue(cutOff(200))
        server.enqueue(cutOff(503))
        assertIs<InstagramException.Transient>(assertFailsWith<InstagramException> { fetch() })
        assertIs<InstagramException.Transient>(assertFailsWith<InstagramException> { fetch() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun getRawReturnsAnyStatusWithItsHeadersAndBodyWithoutThrowing() = runTest {
        server.enqueue(
            MockResponse.Builder().code(404).addHeader("Content-Type", "application/json").body("""{"message":"gone"}""").build(),
        )
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/accounts/login/").build())
        server.enqueue(MockResponse.Builder().code(503).body("down").build())
        val url = server.url("/api/v1/x/")

        val notFound = client.getRaw(url)
        assertEquals(404, notFound.code)
        assertEquals("application/json", notFound.contentType)
        assertEquals("""{"message":"gone"}""", notFound.body)
        assertEquals(null, notFound.location)

        val redirect = client.getRaw(url)
        assertEquals(302, redirect.code)
        assertEquals("/accounts/login/", redirect.location)
        assertEquals("", redirect.body)

        assertEquals(503, client.getRaw(url).code)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun getRawNeverPrintsTheBody() {
        assertEquals("RawResponse(code=200, body=<6 chars>)", RawResponse(200, null, null, "secret").toString())
    }

    @Test
    fun getRawMapsAConnectFailureAndAnUnreadableBodyToTransient() = runTest {
        server.enqueue(cutOff(200))
        assertFailsWith<InstagramException.Transient> { client.getRaw(server.url("/api/v1/x/")) }
        // Unlike getJsonObject, there is no classification from the headers: the caller gets either a response or Transient.
        server.enqueue(cutOff(429))
        assertFailsWith<InstagramException.Transient> { client.getRaw(server.url("/api/v1/x/")) }

        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.close()
        assertFailsWith<InstagramException.Transient> { client.getRaw(url) }
    }
}
