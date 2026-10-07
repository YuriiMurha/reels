package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    private fun ok() = MockResponse.Builder().code(200).body("{}").build()

    /** The host as java.net.URI sees it, which is how [RecordingCookieStore] keys cookies. */
    private fun hostOf(url: HttpUrl): String = URI(url.toString()).host

    /** Runs one GET against the mock server; returns what it threw, or null. */
    private fun get(client: OkHttpClient, path: String = "/x"): Throwable? =
        runCatching { client.newCall(Request.Builder().url(server.url(path)).build()).execute().close() }.exceptionOrNull()

    @Test
    fun debugLoggingNeverShowsRedirectTargets() {
        val marker = "NONCEzz1"
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "https://www.instagram.com/challenge/action/$marker/").build(),
        )
        val lines = mutableListOf<String>()

        HttpClientFactory.create(InMemoryCookieStore(), "UA", logger = { lines += it })
            .newCall(Request.Builder().url(server.url("/api/v1/x/")).build()).execute().close()

        assertTrue("Location: \u2588\u2588" in lines, "the Location header should be logged redacted, got: $lines")
        assertTrue(lines.none { marker in it }, "$marker leaked into the log")
    }

    @Test
    fun debugLoggingNeverShowsInstagramSessionHeaders() {
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("ig-set-authorization", "Bearer zqA")
                .addHeader("X-IG-Set-WWW-Claim", "zqB")
                .addHeader("ig-set-password-encryption-pub-key", "zqC")
                .addHeader("X-Unrelated", "visible1")
                .body("{}").build(),
        )
        val lines = mutableListOf<String>()

        HttpClientFactory.create(InMemoryCookieStore(), "UA", logger = { lines += it })
            .newCall(Request.Builder().url(server.url("/x")).build()).execute().close()

        for (secret in listOf("zqA", "zqB", "zqC")) {
            assertTrue(lines.none { secret in it }, "$secret leaked into the log")
        }
        for (name in listOf("ig-set-authorization", "X-IG-Set-WWW-Claim", "ig-set-password-encryption-pub-key")) {
            assertTrue("$name: \u2588\u2588" in lines, "$name should be logged redacted, got: $lines")
        }
        assertTrue(lines.any { "visible1" in it }, "unrelated headers stay readable")
    }

    @Test
    fun cookiesAreLookedUpForTheRequestHostOnly() {
        val store = RecordingCookieStore().apply {
            put("www.instagram.com", "sessionid", "s1")
            put("www.instagram.com", "csrftoken", "t1")
        }
        val client = HttpClientFactory.create(store, "UA")

        server.enqueue(ok())
        assertNull(get(client))
        val toOtherHost = server.takeRequest()
        assertNull(toOtherHost.headers["Cookie"], "Instagram cookies must never go to another host")
        assertNull(toOtherHost.headers["X-CSRFToken"], "the CSRF token must never go to another host")
        assertTrue(store.lookups.isNotEmpty() && store.lookups.all { URI(it).host == hostOf(server.url("/")) }, "lookups: ${store.lookups}")

        store.put(hostOf(server.url("/")), "sessionid", "s1")
        store.put(hostOf(server.url("/")), "csrftoken", "t1")
        server.enqueue(ok())
        assertNull(get(client))
        val toOwnHost = server.takeRequest()
        assertEquals("sessionid=s1; csrftoken=t1", toOwnHost.headers["Cookie"])
        assertEquals("t1", toOwnHost.headers["X-CSRFToken"])
    }

    @Test
    fun serverSetCookieReachesTheStoreWithItsAttributes() {
        val host = hostOf(server.url("/"))
        val store = RecordingCookieStore()
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Set-Cookie", "sessionid=s2; Domain=$host; Path=/; Max-Age=31536000; Secure; HttpOnly")
                .body("{}").build(),
        )

        assertNull(get(HttpClientFactory.create(store, "UA")))

        val raw = store.written.single().second.lowercase()
        for (attribute in listOf("sessionid=s2", "; domain=$host", "; path=/", "; expires=", "; secure", "; httponly")) {
            assertTrue(attribute in raw, "'$attribute' missing from what reached the store: $raw")
        }
        assertEquals("s2", store.cookieValue(server.url("/").toString(), "sessionid"))
    }

    @Test
    fun serverCookieDeletionReachesTheStore() {
        val host = hostOf(server.url("/"))
        val store = RecordingCookieStore().apply { put(host, "rur", "r1") }
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Set-Cookie", "rur=; Max-Age=0; Path=/").body("{}").build(),
        )

        assertNull(get(HttpClientFactory.create(store, "UA")))

        assertTrue("max-age=0" in store.written.single().second.lowercase(), "written: ${store.written}")
        assertNull(store.cookieValue(server.url("/").toString(), "rur"), "the deleted cookie is gone")
    }

    @Test
    fun aNonAsciiCookieIsSkippedAndTheRequestStillGoesOut() {
        val cookies = InMemoryCookieStore().apply {
            setCookie(site, "a=1")
            setCookie(site, "wd=caf\u00e9")
            setCookie(site, "b=2")
        }
        server.enqueue(ok())

        val failure = get(HttpClientFactory.create(cookies, "UA"))

        assertNull(failure?.message?.takeIf { "caf" in it }, "exception message leaked the cookie value: ${failure?.message}")
        assertNull(failure, "the request must still go out")
        assertEquals("a=1; b=2", server.takeRequest().headers["Cookie"])
    }

    @Test
    fun aCsrfTokenWithAControlCharacterIsNotSentAndNotQuoted() {
        val cookies = InMemoryCookieStore().apply {
            setCookie(site, "sessionid=s1")
            setCookie(site, "csrftoken=t\u0007zq5")
        }
        server.enqueue(ok())

        val failure = get(HttpClientFactory.create(cookies, "UA"))

        assertNull(failure?.message?.takeIf { "zq5" in it }, "exception message leaked the token: ${failure?.message}")
        assertNull(failure, "the request must still go out")
        val recorded = server.takeRequest()
        assertNull(recorded.headers["X-CSRFToken"])
        assertEquals("sessionid=s1", recorded.headers["Cookie"])
    }

    // --- R36: a crash inside the interceptor chain or the cookie bridge must stay an IOException ---

    private class ThrowingCookieStore(private val failure: Throwable = IllegalStateException("secret-zq9")) : CookieStore {
        override fun cookieHeader(url: String): String? = throw failure

        override fun setCookie(url: String, setCookie: String) = Unit

        override fun flush() = Unit

        override fun clearAll() = Unit
    }

    @Test
    fun aCrashingCookieStoreBecomesTransientWithoutLeakingItsMessage() = runTest {
        val client = HttpClientFactory.create(ThrowingCookieStore(), "UA")
        val error = assertFailsWith<InstagramException.Transient> { client.getJsonObject(server.url("/api/v1/x/")) }
        assertFalse("secret-zq9" in error.stackTraceToString(), "no message, cause or suppressed exception may carry the crash text")
        assertEquals(0, server.requestCount)
    }

    /** Class names of everything that reached the default uncaught-exception handler while one call failed on [store]. */
    private suspend fun uncaughtWhileFailingWith(store: CookieStore): List<String> {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val workers = CopyOnWriteArrayList<Thread>()
        val executor = Executors.newCachedThreadPool { task -> Thread(task).also { workers += it } }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val client = HttpClientFactory.create(store, "UA").newBuilder().dispatcher(Dispatcher(executor)).build()
            assertFailsWith<InstagramException.Transient> { client.getJsonObject(server.url("/api/v1/x/")) }
            // Let every worker finish; a rethrown crash would reach the handler before its thread dies.
            executor.shutdown()
            workers.forEach { it.join(5_000) }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        return uncaught.map { it::class.java.name }
    }

    @Test
    fun aCrashingCookieStoreDoesNotKillTheDispatcherThread() = runTest {
        assertEquals(emptyList(), uncaughtWhileFailingWith(ThrowingCookieStore()), "nothing may escape to the uncaught-exception handler")
    }

    // --- R39: checked (non-IO) exceptions are caught too ---

    @Test
    fun aCheckedExceptionFromTheCookieStoreBecomesTransientWithoutLeakingItsMessage() = runTest {
        val client = HttpClientFactory.create(ThrowingCookieStore(TimeoutException("secret-zq8")), "UA")
        val error = assertFailsWith<InstagramException.Transient> { client.getJsonObject(server.url("/api/v1/x/")) }
        assertFalse("secret-zq8" in error.stackTraceToString(), "no message, cause or suppressed exception may carry the crash text")
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aCheckedExceptionFromTheCookieStoreDoesNotKillTheDispatcherThread() = runTest {
        assertEquals(
            emptyList(),
            uncaughtWhileFailingWith(ThrowingCookieStore(TimeoutException("secret-zq8"))),
            "nothing may escape to the uncaught-exception handler",
        )
    }

    @Test
    fun anIoExceptionFromTheCookieStoreIsRethrownAsIs() = runTest {
        val client = HttpClientFactory.create(ThrowingCookieStore(IOException("io-zq7")), "UA")
        val error = assertFailsWith<InstagramException.Transient> { client.getJsonObject(server.url("/api/v1/x/")) }
        assertEquals("io-zq7", error.cause?.message, "the guard must not rewrite an IOException")
    }

    @Test
    fun theSessionProbeReportsACrashingClientAsTransient() = runTest {
        val good = InMemoryCookieStore().apply { setCookie(site, "ds_user_id=42") }
        val probe = WebSessionProbe(HttpClientFactory.create(ThrowingCookieStore(), "UA"), good, base = server.url("/"))
        assertFailsWith<InstagramException.Transient> { probe.currentUser() }
    }

    // --- R37: OkHttp must never silently re-send a request outside the Pacer ---

    @Test
    fun aStaleKeepAliveConnectionIsNotSilentlyResent() = runTest {
        // OkHttp only retries a connection that already served a request, so warm one up first.
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val client = HttpClientFactory.create(InMemoryCookieStore(), "UA")
        client.getJsonObject(server.url("/api/v1/x/"))
        assertFailsWith<InstagramException.Transient> { client.getJsonObject(server.url("/api/v1/x/")) }
        assertEquals(2, server.requestCount, "the third queued response must never be fetched")
    }

    // --- R39: OkHttp's 503 + "Retry-After: 0" follow-up is a second request below the Pacer ---

    @Test
    fun aServiceUnavailableWithRetryAfterZeroIsNotSilentlyResent() = runTest {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").build())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val client = HttpClientFactory.create(InMemoryCookieStore(), "UA")
        assertFailsWith<InstagramException.Transient> { client.getJsonObject(server.url("/api/v1/x/")) }
        assertEquals(1, server.requestCount, "the queued 200 must never be fetched")
    }

    @Test
    fun debugLoggingStillSeesTheOriginalRetryAfterHeader() = runTest {
        val lines = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "7").build())
        val client = HttpClientFactory.create(InMemoryCookieStore(), "UA") { lines += it }
        assertFailsWith<InstagramException.Transient> { client.getJsonObject(server.url("/api/v1/x/")) }
        assertTrue(lines.any { it.startsWith("Retry-After: 7") }, "the wire log shows what Instagram really sent")
    }
}
