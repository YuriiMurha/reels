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
        assertEquals("http.404", assertFailsWith<InstagramException.ShapeChanged> { fetch() }.fieldPath)
    }

    @Test
    fun aSuccessOrServerErrorWithAnUnreadableBodyIsTransient() = runTest {
        server.enqueue(cutOff(200))
        server.enqueue(cutOff(503))
        assertIs<InstagramException.Transient>(assertFailsWith<InstagramException> { fetch() })
        assertIs<InstagramException.Transient>(assertFailsWith<InstagramException> { fetch() })
        assertEquals(2, server.requestCount)
    }
}
