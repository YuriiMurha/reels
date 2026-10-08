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
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What a reply whose body is cut off signals, and the header-only classification behind it (cut bodies: [CutResponse]). */
class WebJsonTest {
    private val server = MockWebServer()
    private val client = HttpClientFactory.create(InMemoryCookieStore(), "UA")

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun cutOff(code: Int, location: String? = null): MockResponse = cutResponse(code, location)

    private suspend fun fetch() = client.getJsonObject(server.url("/api/v1/x/"))

    @Test
    fun aChallengeRedirectStopsEvenWhenItsBodyCannotBeRead() = runTest {
        server.enqueue(cutOff(302, location = "/challenge/"))
        val error = assertFailsWith<InstagramException.ChallengeRequired> { fetch() }
        // A redirect is not followed and its body is not read, so the target is never kept (the owner opens the WebView).
        assertNull(error.challengeUrl)
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
    fun anUnreadableBodyIsClassifiedFromTheHeadersAlone() {
        assertIs<InstagramException.RateLimited>(classifyUnreadable(429, null, null))
        assertIs<InstagramException.LoginRequired>(classifyUnreadable(401, null, null))
        assertIs<InstagramException.LoginRequired>(classifyUnreadable(403, null, null))
        assertIs<InstagramException.LoginRequired>(classifyUnreadable(302, "/accounts/login/?next=/", null))
        val challenge = assertIs<InstagramException.ChallengeRequired>(classifyUnreadable(302, "/challenge/x/", null))
        assertEquals("https://www.instagram.com/challenge/x/", challenge.challengeUrl)
        assertIs<InstagramException.ChallengeRequired>(classifyUnreadable(301, null, null))
        // A plain 4xx is marked unreadable, never a bare http.<code> a caller could read as "not found".
        for (code in listOf(400, 404, 410, 418)) {
            assertEquals("http.$code.unreadable", assertIs<InstagramException.ShapeChanged>(classifyUnreadable(code, null, null)).fieldPath)
        }
    }
}
