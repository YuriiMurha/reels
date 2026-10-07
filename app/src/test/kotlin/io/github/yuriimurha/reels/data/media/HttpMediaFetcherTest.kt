package io.github.yuriimurha.reels.data.media

import io.github.yuriimurha.reels.instagram.web.HttpClientFactory
import io.github.yuriimurha.reels.instagram.web.InMemoryCookieStore
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The CDN fetcher over MockWebServer (plain http), so it needs the test-only `requireHttps = false`; a separate test
 * pins that the default refuses http.
 */
class HttpMediaFetcherTest {
    private val server = MockWebServer()
    private val cdn = HttpMediaFetcher.client("TestWebView/1.0")
    private val bytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun fetcher(client: OkHttpClient = cdn) = HttpMediaFetcher({ client }, requireHttps = false)

    private fun url(path: String = "/v/t51/1.jpg") = server.url(path).toString()

    private fun respond(code: Int, body: ByteArray = ByteArray(0)) =
        server.enqueue(MockResponse.Builder().code(code).body(Buffer().write(body)).build())

    @Test
    fun fetcherMapsStatusCodes() = runBlocking {
        val fetcher = fetcher()

        respond(200, bytes)
        assertContentEquals(bytes, fetcher.fetch(url()))

        for (code in listOf(403, 404, 410)) {
            respond(code, "gone".toByteArray())
            assertNull(fetcher.fetch(url()), "HTTP $code means this item has no thumbnail: null, not an error")
        }

        respond(429)
        assertFailsWith<CdnRateLimited> { fetcher.fetch(url()) }

        for (code in listOf(500, 503, 400, 302)) {
            respond(code)
            val failure = assertFailsWith<IOException>("HTTP $code") { fetcher.fetch(url()) }
            assertFalse(failure is CdnRateLimited, "only a 429 is a CDN rate limit (HTTP $code)")
        }

        assertEquals(1 + 3 + 1 + 4, server.requestCount, "one request per fetch: nothing is re-sent")
    }

    @Test
    fun aBlankUrlIsNullWithNoRequest() = runBlocking {
        val fetcher = fetcher()
        for (blank in listOf("", " ", "\n\t")) assertNull(fetcher.fetch(blank))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnparseableOrFakeUrlIsNullWithNoRequest() = runBlocking {
        val fetcher = fetcher()
        for (junk in listOf("not a url", "fake://thumb/1", "/relative/path.jpg", "https://")) assertNull(fetcher.fetch(junk), junk)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theDefaultRefusesAnHttpUrlWithNoRequest() = runBlocking {
        val strict = HttpMediaFetcher({ cdn }) // requireHttps defaults to true
        respond(200, bytes)
        assertNull(strict.fetch(url()), "MockWebServer serves http, which the default must refuse")
        assertEquals(0, server.requestCount)

        assertNull(HttpMediaFetcher({ cdn }, requireHttps = true).fetch(url()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theHttpClientIsOnlyBuiltByARequestThatReachesTheNetwork() = runBlocking {
        var built = 0
        val fetcher = HttpMediaFetcher({ built++; cdn })
        assertNull(fetcher.fetch(""))
        assertNull(fetcher.fetch(url())) // http, refused
        assertEquals(0, built, "a refused url must not build the client (its user agent comes from the WebView provider)")

        val lenient = HttpMediaFetcher({ built++; cdn }, requireHttps = false)
        respond(200, bytes)
        lenient.fetch(url())
        respond(200, bytes)
        lenient.fetch(url())
        assertEquals(1, built, "built once, on the first real request")
    }

    @Test
    fun noCookieHeaderIsSentEvenWhenTheStoreHoldsInstagramCookies() = runBlocking {
        // Built from parts so no literal session cookie sits in the source. InMemoryCookieStore ignores domains, so the
        // control below proves these cookies WOULD ride on a request to this very server through the API client.
        val site = "https://www.instagram.com"
        val cookies = InMemoryCookieStore().apply {
            setCookie(site, "session" + "id=s1")
            setCookie(site, "csrf" + "token=t1")
        }

        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        HttpClientFactory.create(cookies, "TestWebView/1.0")
            .newCall(Request.Builder().url(server.url("/control")).build()).execute().close()
        assertEquals("session" + "id=s1; csrf" + "token=t1", server.takeRequest().headers["Cookie"], "the control sends the cookies")

        respond(200, bytes)
        fetcher().fetch(url())
        val recorded = server.takeRequest()
        assertNull(recorded.headers["Cookie"], "the CDN client has no jar: a cookie never reaches the CDN")
        assertNull(recorded.headers["X-CSRFToken"])
        assertNull(recorded.headers["X-IG-App-ID"])
        assertNull(recorded.headers["Referer"])
    }

    @Test
    fun sendsTheWebViewUserAgent() = runBlocking {
        respond(200, bytes)
        fetcher().fetch(url())
        assertEquals("TestWebView/1.0", server.takeRequest().headers["User-Agent"])
    }

    @Test
    fun theCdnClientHasNoJarNoRetriesNoRedirectsAndTheAgreedTimeouts() {
        assertSame(CookieJar.NO_COOKIES, cdn.cookieJar, "no cookie jar at all")
        assertFalse(cdn.retryOnConnectionFailure, "OkHttp must not silently re-send a download")
        assertEquals(15_000, cdn.connectTimeoutMillis)
        assertEquals(30_000, cdn.readTimeoutMillis)
        assertFalse(cdn.followRedirects, "a redirect is a failed download, never a second request")
        assertFalse(cdn.followSslRedirects, "an https URL must not be bounced to cleartext http")
    }

    // --- R66: OkHttp's own follow-ups are not covered by retryOnConnectionFailure(false) ---

    /** OkHttp re-sends after a 503 that says "Retry-After: 0" by itself. The 200 queued behind it must stay unused. */
    @Test
    fun aRetryAfterZero503IsOneRequestAndAnIOException() = runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
        respond(200, bytes)

        val failure = assertFailsWith<IOException> { fetcher().fetch(url()) }

        assertFalse(failure is CdnRateLimited)
        assertEquals(1, server.requestCount, "one download, one request: nothing is re-sent behind the pacer's back")
    }

    @Test
    fun aRedirectIsAFailedDownloadAndItsTargetIsNeverRequested() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere").toString()).build())
        respond(200, bytes)

        val failure = assertFailsWith<IOException> { fetcher().fetch(url("/v/t51/1.jpg")) }

        assertEquals("CDN returned HTTP 302", failure.message, "the code only: never the URL")
        assertEquals(1, server.requestCount)
        assertEquals("/v/t51/1.jpg", server.takeRequest().url.encodedPath, "the redirect target was never requested")
    }

    @Test
    fun aMisdirected421IsOneRequestAndAnIOException() = runBlocking {
        server.enqueue(MockResponse.Builder().code(421).build())
        respond(200, bytes)

        assertFailsWith<IOException> { fetcher().fetch(url()) }

        assertEquals(1, server.requestCount)
    }
}
