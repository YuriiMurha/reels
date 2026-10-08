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
import kotlin.test.assertNull

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

    private fun probe() = WebSessionProbe({ OkHttpTransport(HttpClientFactory.create(cookies, "UA"), server.url("/")) }, cookies)

    private fun serve(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun returnsTheLoggedInAccount() = runTest {
        serve("""{"form_data":{"username":"user_1"},"status":"ok"}""")
        assertEquals(Account("42", "user_1"), probe().currentUser())
        val request = server.takeRequest().url
        assertEquals("/api/v1/accounts/edit/web_form_data/", request.encodedPath)
        assertNull(request.encodedQuery)
    }

    @Test
    fun thePkIsTheCookiesUserIdWhateverTheBodySays() = runTest {
        serve("""{"form_data":{"username":"user_1","pk":99},"pk":98,"status":"ok"}""")
        assertEquals(Account("42", "user_1"), probe().currentUser())
    }

    @Test
    fun noUserCookieMeansLoggedOutWithoutARequest() = runTest {
        cookies.clearAll()
        assertFailsWith<InstagramException.LoginRequired> { probe().currentUser() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRedirectToTheLoginPageIsLoggedOut() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/accounts/login/?next=/").build())
        assertFailsWith<InstagramException.LoginRequired> { probe().currentUser() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anyOtherRedirectIsNotFollowedAndIsAChallengeWithNoUrl() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/challenge/?next=/").build())
        val error = assertFailsWith<InstagramException.ChallengeRequired> { probe().currentUser() }
        assertNull(error.challengeUrl, "the target of a redirect is unknown to a browser fetch, so it is never kept")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun missingFormDataNeedsRepair() = runTest {
        serve("""{"user":{"username":"user_1"},"status":"ok"}""")
        val error = assertFailsWith<InstagramException.ShapeChanged> { probe().currentUser() }
        assertEquals("form_data", error.fieldPath)
    }

    @Test
    fun aFormDataThatIsNotAnObjectNeedsRepair() = runTest {
        serve("""{"form_data":"x","status":"ok"}""")
        assertEquals("form_data", assertFailsWith<InstagramException.ShapeChanged> { probe().currentUser() }.fieldPath)
    }

    @Test
    fun missingUsernameNeedsRepair() = runTest {
        serve("""{"form_data":{},"status":"ok"}""")
        val error = assertFailsWith<InstagramException.ShapeChanged> { probe().currentUser() }
        assertEquals("form_data.username", error.fieldPath)
    }

    @Test
    fun networkFailureIsTransient() = runTest {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/")
        dead.close()
        val unreachable = WebSessionProbe({ OkHttpTransport(HttpClientFactory.create(cookies, "UA"), url) }, cookies)
        assertFailsWith<InstagramException.Transient> { unreachable.currentUser() }
    }

    @Test
    fun aUserIdThatIsNotDigitsIsTreatedAsLoggedOutWithoutARequest() = runTest {
        for (bad in listOf("..", "42a", "4 2", "%2e%2e")) {
            cookies.clearAll()
            cookies.setCookie("https://www.instagram.com", "ds_user_id=$bad")
            assertFailsWith<InstagramException.LoginRequired>(bad) { probe().currentUser() }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theTransportIsNotAskedWithoutASession() = runTest {
        var asked = 0
        cookies.clearAll()
        val probe = WebSessionProbe({ asked++; OkHttpTransport(HttpClientFactory.create(cookies, "UA"), server.url("/")) }, cookies)
        assertFailsWith<InstagramException.LoginRequired> { probe.currentUser() }
        assertEquals(0, asked)
    }
}
