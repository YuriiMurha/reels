package io.github.yuriimurha.reels.instagram.web

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpClientFactoryTest {
    private val server = MockWebServer()
    private val site = "https://www.instagram.com"

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    @Test
    fun sendsTheWebViewIdentityAndTheSharedCookies() {
        val cookies = InMemoryCookieStore().apply {
            setCookie(site, "sessionid=s1")
            setCookie(site, "csrftoken=t1")
        }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "rur=r2; Path=/").body("{}").build())

        HttpClientFactory.create(cookies, userAgent = "TestWebView/1.0")
            .newCall(Request.Builder().url(server.url("/api/v1/x/")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("TestWebView/1.0", recorded.headers["User-Agent"])
        assertEquals(WebHeaders.APP_ID, recorded.headers["X-IG-App-ID"])
        assertEquals("t1", recorded.headers["X-CSRFToken"])
        assertEquals("sessionid=s1; csrftoken=t1", recorded.headers["Cookie"])
        assertEquals("r2", cookies.cookieValue(site, "rur"), "Instagram's cookie update reaches the shared store")
        assertTrue(cookies.flushes > 0)
    }

    @Test
    fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/challenge/?next=/").build())
        val response = HttpClientFactory.create(InMemoryCookieStore(), "UA")
            .newCall(Request.Builder().url(server.url("/api/v1/x/")).build()).execute()
        assertEquals(302, response.code)
        response.close()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun debugLoggingNeverShowsCookieValues() {
        val cookies = InMemoryCookieStore().apply {
            setCookie(site, "sessionid=zq9")
            setCookie(site, "csrftoken=zq8")
        }
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "rur=zq7; Path=/").body("{}").build())
        val lines = mutableListOf<String>()

        HttpClientFactory.create(cookies, "UA", logger = { lines += it })
            .newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        assertTrue(lines.isNotEmpty())
        for (secret in listOf("zq9", "zq8", "zq7")) {
            assertTrue(lines.none { secret in it }, "$secret leaked into the log")
        }
    }
}
