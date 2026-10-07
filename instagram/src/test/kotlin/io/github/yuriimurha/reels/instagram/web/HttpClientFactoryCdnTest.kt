package io.github.yuriimurha.reels.instagram.web

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CookieJar
import okhttp3.Request
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R66: the CDN client spans many hosts under wildcard certificates, so P6's one-host argument doesn't cover it, and
 * `retryOnConnectionFailure(false)` alone stops neither OkHttp's 503 "Retry-After: 0" follow-up nor its re-send after
 * a 421 on a coalesced HTTP/2 connection. Every request must reach the wire exactly once.
 */
class HttpClientFactoryCdnTest {
    private val server = MockWebServer()
    private val client = HttpClientFactory.createCdn("TestWebView/1.0")

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun get(path: String = "/v/t51/1.jpg") = client.newCall(Request.Builder().url(server.url(path)).build())

    private fun ok() = MockResponse.Builder().code(200).body("bytes").build()

    @Test
    fun hasNoJarNoRetriesNoRedirectsAndTheAgreedTimeouts() {
        assertSame(CookieJar.NO_COOKIES, client.cookieJar, "no cookie jar at all")
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
    }

    /**
     * Only a NETWORK interceptor sees a 503 or a 421 before OkHttp's own follow-up logic can re-send the request; an
     * application interceptor sits above that logic and would see the answer too late. So both rules must be network
     * interceptors, by identity, and neither may be registered a second time as an application one.
     */
    @Test
    fun the503And421RulesAreNetworkInterceptorsNotApplicationOnes() {
        assertEquals(2, client.networkInterceptors.size, "exactly the two re-send rules: ${client.networkInterceptors}")
        assertTrue(client.networkInterceptors.any { it === noRetryAfterOn503 }, "the 503 Retry-After rule must be a network interceptor")
        assertTrue(client.networkInterceptors.any { it === misdirectedIsAFailure }, "the 421 rule must be a network interceptor")
        assertFalse(
            client.interceptors.any { it === noRetryAfterOn503 || it === misdirectedIsAFailure },
            "an application interceptor sees the response only after OkHttp has re-sent",
        )
    }

    /** With retries off OkHttp can't recover from a thrown 421: the exception is the end of the call. */
    @Test
    fun a421BecomesAnIOExceptionAfterOneRequest() {
        server.enqueue(MockResponse.Builder().code(421).build())
        server.enqueue(ok())

        val failure = assertFailsWith<IOException> { get().execute() }

        assertEquals("CDN 421", failure.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a503WithRetryAfterZeroIsNotResent() {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
        server.enqueue(ok())

        get().execute().use { response ->
            assertEquals(503, response.code)
            assertNull(response.header("Retry-After"), "the header that makes OkHttp re-send is stripped")
        }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRedirectIsReturnedNotFollowed() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere").toString()).build())
        server.enqueue(ok())

        get("/v/t51/1.jpg").execute().use { response ->
            assertEquals(302, response.code)
            assertNotNull(response.header("Location"))
        }

        assertEquals(1, server.requestCount)
        assertEquals("/v/t51/1.jpg", server.takeRequest().url.encodedPath)
    }

    @Test
    fun theUserAgentIsReducedToPrintableAscii() {
        val client = HttpClientFactory.createCdn("Mozilla/5.0 (Pixel é)\nEvil: x\u0007\t")
        server.enqueue(ok())

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close() // must not throw

        val sent = assertNotNull(server.takeRequest().headers["User-Agent"])
        assertTrue(sent.isNotEmpty() && sent.all { it in ' '..'~' }, "not printable ASCII: $sent")
        assertEquals("Mozilla/5.0 (Pixel )Evil: x", sent)
    }

    @Test
    fun noCookieOrInstagramHeaderIsEverSent() {
        server.enqueue(ok())
        get().execute().close()
        val recorded = server.takeRequest()
        assertNull(recorded.headers["Cookie"])
        assertNull(recorded.headers["X-CSRFToken"])
        assertNull(recorded.headers["X-IG-App-ID"])
        assertNull(recorded.headers["Referer"])
        assertEquals("TestWebView/1.0", recorded.headers["User-Agent"])
    }

    /** The outermost guard: an unchecked crash in the chain must leave as an IOException that names only its class. */
    @Test
    fun aCrashInsideTheChainStaysAnIOException() {
        val crashing = client.newBuilder().addInterceptor { throw IllegalStateException("secret-zq9") }.build()

        val failure = assertFailsWith<IOException> { crashing.newCall(Request.Builder().url(server.url("/x")).build()).execute() }

        assertFalse("secret-zq9" in failure.stackTraceToString())
        assertEquals(0, server.requestCount)
    }
}
