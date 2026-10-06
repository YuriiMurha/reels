package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebSessionProbeTest {
    private val server = MockWebServer()
    private val cookies = InMemoryCookieStore()

    @BeforeTest
    fun start() {
        server.start()
        cookies.setCookie("https://www.instagram.com", "ds_user_id=42")
    }

    @AfterTest
    fun stop() = server.close()

    private fun probe() = WebSessionProbe(HttpClientFactory.create(cookies, "UA"), cookies, base = server.url("/"))

    @Test
    fun returnsTheLoggedInAccount() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"user":{"pk":42,"username":"tester"},"status":"ok"}""").build())
        assertEquals(Account("42", "tester"), probe().currentUser())
        assertEquals("/api/v1/users/42/info/", server.takeRequest().url.encodedPath)
    }

    @Test
    fun noUserCookieMeansLoggedOutWithoutARequest() = runTest {
        cookies.clearAll()
        assertFailsWith<InstagramException.LoginRequired> { probe().currentUser() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun challengeRedirectIsNotFollowed() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/challenge/?next=/").build())
        assertFailsWith<InstagramException.ChallengeRequired> { probe().currentUser() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingUsernameNeedsRepair() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"user":{},"status":"ok"}""").build())
        val error = assertFailsWith<InstagramException.ShapeChanged> { probe().currentUser() }
        assertEquals("user.username", error.fieldPath)
    }

    @Test
    fun networkFailureIsTransient() = runTest {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.close()
        val unreachable = WebSessionProbe(HttpClientFactory.create(cookies, "UA"), cookies, base = url)
        assertFailsWith<InstagramException.Transient> { unreachable.currentUser() }
    }
}
