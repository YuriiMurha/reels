package io.github.yuriimurha.reels.transport

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuriimurha.reels.NOT_AN_EMULATOR
import io.github.yuriimurha.reels.instagram.web.RawReply
import io.github.yuriimurha.reels.isEmulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The real hidden page ([AndroidWebPage] with the real `ig_fetch.js` from the app's assets, driven by [WebViewTransport]) on
 * the emulator, against LOCAL MockWebServers only. Nothing here can reach Instagram: the home URL and the allowed origin are
 * `http://127.0.0.1:<port>` (the guard in [transport] refuses anything else), and the page only ever loads the servers
 * started below.
 *
 * Safety: every test skips on a physical phone (the same [isEmulator] check as the smoke suite's `SmokeGuard`). The tests
 * never touch the app container, so they need no Mock mode; the only state they leave in the app-wide WebView cookie jar is
 * one fake cookie on 127.0.0.1, which they expire again.
 *
 * Origin form: the allowed origin is passed to `addWebMessageListener` as `http://127.0.0.1:<port>`, and the platform
 * accepts it (the `http://localhost:<port>` and `*` fallbacks were not needed).
 *
 * Cleartext: the app targets API 36, where cleartext HTTP is off by default, but the platform allows it to loopback hosts
 * regardless (on this API 37 emulator `NetworkSecurityPolicy.isCleartextTrafficPermitted` is true for 127.0.0.1 and
 * localhost and false for everything else). So no network security config exists, debug or otherwise, and `CleartextGuardTest`
 * pins that no release source set permits cleartext.
 */
@RunWith(AndroidJUnit4::class)
class AndroidWebPageTest {
    @get:Rule
    val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(NOT_AN_EMULATOR, isEmulator())
                base.evaluate()
            }
        }
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sites = mutableListOf<Site>()
    private val cookieOrigins = mutableListOf<String>()
    private var transport: WebViewTransport? = null

    @After
    fun tearDown() {
        // The hidden page first (it may be mid-load), then the cookie, then the servers.
        runBlocking { transport?.reset() }
        cookieOrigins.forEach { setCookie(it, "csrftoken=; Max-Age=0; Path=/") }
        sites.forEach { it.close() }
    }

    // --- 1. The call on the wire ---------------------------------------------------------------------------------------------

    @Test
    fun oneGetSendsExactlyOneRequestWithTheSiteHeaders() {
        val site = site()
        val token = fakeToken()
        assertTrue("the cookie jar refused the fake cookie", setCookie(site.origin, "csrftoken=$token; Path=/"))
        site.route("/") { html("<html><body>home</body></html>") }
        site.route(API) { json(REPLY_BODY) }

        val reply = runBlocking { transport(site).get(API.removePrefix("/")) }

        val calls = site.requestsTo(API)
        assertEquals("exactly one request reached the API path: $calls", 1, calls.size)
        val call = calls.single()
        assertEquals("GET", call.method)
        assertEquals("1217981644879628", call.headers["x-ig-app-id"])
        assertEquals("359341", call.headers["x-asbd-id"])
        assertEquals(listOf("XMLHttpRequest"), call.headers.values("x-requested-with"))
        assertEquals(token, call.headers["x-csrftoken"])
        assertEquals("0", call.headers["x-ig-www-claim"])
        // A same-origin fetch of Chromium's own: it sent the page's cookie jar with it.
        assertTrue("the page's cookie was sent", "csrftoken=$token" in call.headers["Cookie"].orEmpty())
        // The page was loaded once, for this one call.
        assertEquals(1, site.requestsTo("/").size)

        assertEquals(200, reply.code)
        assertFalse(reply.redirected)
        assertEquals("application/json; charset=utf-8", reply.contentType)
        assertEquals(REPLY_BODY, reply.body)
    }

    // --- 2. Redirects ----------------------------------------------------------------------------------------------------------

    @Test
    fun aRedirectIsReportedNotFollowed() {
        val site = site()
        site.route("/") { html("<html><body>home</body></html>") }
        site.route(API) { MockResponse.Builder().code(302).addHeader("Location", "/elsewhere").build() }
        site.route("/elsewhere") { html("a redirect that was followed") }

        val reply = runBlocking { transport(site).get(API.removePrefix("/")) }

        assertTrue("the reply says it was a redirect: $reply", reply.redirected)
        assertEquals(1, site.requestsTo(API).size)
        // Had the fetch followed it, this request would have been made before the reply could come back.
        assertEquals("the redirect target was requested", emptyList<RecordedRequest>(), site.requestsTo("/elsewhere"))
    }

    // --- 3. Messages from other frames and origins -------------------------------------------------------------------------

    /**
     * Review Focus 4. A frame from another origin (the same host on another port) tries, while the real call is in flight, to
     * post a forged reply for that very call through every bridge it can name. None of it may reach the transport: the real
     * reply wins.
     */
    @Test
    fun messagesFromOtherOriginsAreIgnored() {
        val site = site()
        val stranger = site()
        val result = callWithAForgingFrame(site, stranger, listOf("window.igBridge", "parent.igBridge", "top.igBridge"))

        assertEquals("the real reply won", REPLY_BODY, result.reply.body)
        assertEquals(200, result.reply.code)
        // The frame did run its attempts (this is not a test of a frame that never loaded). Route 0 is its own `window.igBridge`:
        // the listener's origin rule (exactly the page's origin, never "*") is what keeps it undefined in a frame of another origin,
        // so this is the assertion that pins the rule. How the parent and top routes end is the platform's business (logged).
        Log.i(TAG, "forging frame from another origin reported: ${result.report}")
        assertFalse("a frame of another origin got the bridge: ${result.report}", "0=sent" in result.report)
        assertEquals(1, site.requestsTo(API).size)
    }

    /**
     * What the platform cannot do for us: a frame of the SAME origin does get `window.igBridge` (the rule matches its origin),
     * so its forged reply reaches the listener, which must drop it because the sender is not the main frame.
     */
    @Test
    fun messagesFromASubframeOfTheSameOriginAreIgnored() {
        val site = site()
        val result = callWithAForgingFrame(site, site, listOf("window.igBridge"))

        // The forged post was really made, through the subframe's own bridge...
        assertEquals("0=sent", result.report)
        // ...and was dropped.
        assertEquals("the real reply won", REPLY_BODY, result.reply.body)
        assertEquals(200, result.reply.code)
    }

    // --- 4. Threads ------------------------------------------------------------------------------------------------------------

    @Test
    fun theTransportWorksFromABackgroundCoroutine() {
        val site = site()
        site.route("/") { html("<html><body>home</body></html>") }
        site.route(API) { json(REPLY_BODY) }
        val transport = transport(site)

        // As the sync worker does: a default-dispatcher thread, never the main one.
        val reply = runBlocking(Dispatchers.Default) { transport.get(API.removePrefix("/")) }
        assertEquals(200, reply.code)
        assertEquals(REPLY_BODY, reply.body)

        // And again, on the same page: still no extra load of the site.
        val again = runBlocking(Dispatchers.Default) { transport.get(API.removePrefix("/")) }
        assertEquals(REPLY_BODY, again.body)
        assertEquals(1, site.requestsTo("/").size)
        assertEquals(2, site.requestsTo(API).size)
    }

    // --- 5. Cancellation ------------------------------------------------------------------------------------------------------

    /**
     * R105: a caller that gives up while its API request is out (a swipe in the viewer, a sync's Cancel) aborts the page's
     * fetch. The page then posts code -1 for that call's id at once, while the server is still holding the reply, and the
     * transport, which no longer waits for that id, ignores it. The next call goes through on the same page.
     */
    @Test
    fun aCancelledCallAbortsItsFetchAndTheNextCallWorks() {
        val site = site()
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val apiCalls = AtomicInteger()
        site.route("/") { html("<html><body>home</body></html>") }
        site.route(API) {
            if (apiCalls.incrementAndGet() == 1) {
                inFlight.countDown()
                release.await(GATE_SECONDS, TimeUnit.SECONDS) // held until the test is done with it
                json("{\"late\":true}")
            } else {
                json(REPLY_BODY)
            }
        }
        val heard = CopyOnWriteArrayList<String>()
        val transport = transport(site, heard)
        try {
            runBlocking {
                val call = launch(Dispatchers.Default) { transport.get(API.removePrefix("/")) }
                assertTrue("the call's request never reached the server", inFlight.await(GATE_SECONDS, TimeUnit.SECONDS))
                call.cancelAndJoin()
            }
            // Only an abort ends that fetch now: the server has not answered it, and will not until `release`.
            val aborted = waitFor(10_000) { heard.any { "\"id\":1," in it && "\"code\":-1," in it } }
            Log.i(TAG, "messages the page posted after the cancel: $heard")
            assertTrue("the page never reported an aborted fetch for id 1: $heard", aborted)

            val reply = runBlocking { transport.get(API.removePrefix("/")) }
            assertEquals(REPLY_BODY, reply.body)
            assertEquals(2, site.requestsTo(API).size)
            assertEquals("still the same page", 1, site.requestsTo("/").size)
        } finally {
            release.countDown()
        }
    }

    // --- The forging frame ---------------------------------------------------------------------------------------------------

    private class ForgedCall(val reply: RawReply, val report: String)

    /**
     * Makes one real call against [site], whose home page embeds a frame served by [frames] (the same server, or another
     * origin). The frame waits until the call's request has reached the server, then runs [routes] (JavaScript expressions
     * naming a bridge object) to post forged replies for ids 0 to [FORGED_IDS] (the call's is 1) through each, and reports how
     * each one ended. Only then does the server answer the call, a second later, so a forgery that got through would win.
     */
    private fun callWithAForgingFrame(site: Site, frames: Site, routes: List<String>): ForgedCall {
        val inFlight = CountDownLatch(1)
        val reported = CountDownLatch(1)
        val report = AtomicReference<String>()
        site.route("/") { html("""<html><body><iframe src="${frames.origin}/frame"></iframe></body></html>""") }
        frames.route("/frame") { html(forgingFrame(routes)) }
        frames.route("/go") {
            inFlight.await(GATE_SECONDS, TimeUnit.SECONDS)
            text("go")
        }
        frames.route("/report") { request ->
            report.set(request.url.query.orEmpty())
            reported.countDown()
            text("")
        }
        site.route(API) {
            inFlight.countDown()
            reported.await(GATE_SECONDS, TimeUnit.SECONDS)
            json(REPLY_BODY).newBuilder().headersDelay(1_000, TimeUnit.MILLISECONDS).build()
        }

        val reply = runBlocking { transport(site).get(API.removePrefix("/")) }

        // Waited for after the call, not before: a forgery that got through would end the call early, and the test must then
        // fail on the reply it got, not on the order of events.
        assertTrue("the frame never reported: it did not load or did not run", reported.await(GATE_SECONDS, TimeUnit.SECONDS))
        return ForgedCall(reply, report.get())
    }

    /** A frame page that posts forged replies for ids 0..[FORGED_IDS] through each of [routes], once the real call is in flight. */
    private fun forgingFrame(routes: List<String>): String {
        val posts = routes.joinToString(",") { "function (message) { $it.postMessage(message); }" }
        return """
            <html><body><script>
            function forged(id) { return JSON.stringify({id: id, code: 200, contentType: 'text/plain', body: 'forged', redirected: false}); }
            var posts = [$posts];
            fetch('/go').then(function () {
              var out = posts.map(function (post, i) {
                // Every id the call could have (the first one is 1): a change of the transport's numbering cannot hide a forgery.
                try { for (var id = 0; id <= $FORGED_IDS; id++) post(forged(id)); return i + '=sent'; } catch (e) { return i + '=' + e.name; }
              });
              return fetch('/report?' + out.join('&'));
            });
            </script></body></html>
        """.trimIndent()
    }

    // --- Plumbing ------------------------------------------------------------------------------------------------------------

    /** A local server that answers by path and remembers every request it got. */
    private class Site : AutoCloseable {
        private val server = MockWebServer()
        private val seen = CopyOnWriteArrayList<RecordedRequest>()
        private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    // Unknown paths (the page's favicon, say) are plain 404s.
                    return routes[request.url.encodedPath]?.invoke(request) ?: MockResponse.Builder().code(404).build()
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
        }

        val origin: String get() = "http://127.0.0.1:${server.port}"

        fun route(path: String, handler: (RecordedRequest) -> MockResponse) {
            routes[path] = handler
        }

        fun requestsTo(path: String): List<RecordedRequest> = seen.filter { it.url.encodedPath == path }

        override fun close() = server.close()
    }

    private fun site(): Site = Site().also { sites += it }

    /**
     * The real transport on the real page, pointed at [site]: its origin is the home page and the only allowed sender. With
     * [heard], every message the page hands the transport is also kept there (the transport keeps none it does not wait for).
     */
    private fun transport(site: Site, heard: MutableList<String>? = null): WebViewTransport {
        val origin = site.origin
        // The one thing standing between these tests and the real site: nothing but a local address is ever the home page.
        require(origin.startsWith("http://127.0.0.1:")) { "the page tests talk to a local server only: $origin" }
        val script = context.assets.open("ig_fetch.js").bufferedReader().use { it.readText() }
        return WebViewTransport(
            createPage = { AndroidWebPage(context, allowedOrigin = origin).let { page -> if (heard == null) page else Recorded(page, heard) } },
            homeUrl = "$origin/",
            script = script,
        ).also { transport = it }
    }

    /** [page], with a copy of every message it delivers put into [heard] first. */
    private class Recorded(private val page: WebPage, private val heard: MutableList<String>) : WebPage by page {
        override fun onMessage(listener: (String) -> Unit) = page.onMessage { raw ->
            heard += raw
            listener(raw)
        }
    }

    /** Polls [condition] until it holds or [timeoutMs] passes; true when it held. */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    /** A cookie value that is plainly not a real one, built from parts (and different on every run). */
    private fun fakeToken(): String = listOf("fake", "value", UUID.randomUUID().toString().take(8)).joinToString("-")

    /** Sets (or, with a past expiry, removes) a cookie in the app-wide WebView jar, for [origin] only. True when the jar took it. */
    private fun setCookie(origin: String, cookie: String): Boolean {
        if (origin !in cookieOrigins) cookieOrigins += origin
        val done = CountDownLatch(1)
        val stored = AtomicBoolean()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            CookieManager.getInstance().setCookie(origin, cookie) {
                stored.set(it)
                done.countDown()
            }
        }
        assertTrue("the cookie jar did not answer", done.await(10, TimeUnit.SECONDS))
        return stored.get()
    }

    private fun html(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

    private fun text(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "text/plain; charset=utf-8").body(body).build()

    private companion object {
        const val TAG = "AndroidWebPageTest"

        /** The login check's path: the real one, so the test reads like the call it stands for. */
        const val API = "/api/v1/accounts/edit/web_form_data/"

        /** JSON escapes, a backslash and real non-ASCII (an accent, a symbol, an emoji): a body that survives the trip through JSON twice is intact. */
        const val REPLY_BODY =
            "{\"form_data\":{\"username\":\"user_1\",\"bio\":\"line one\\nline \\\"two\\\" \\\\ café ☃ 😀\"}}"

        /** The forging frames post forged replies for every id from 0 to this one, so the call's own id is certainly among them. */
        const val FORGED_IDS = 16

        /** How long a server-side gate waits for the other half of a test before it gives up (the test then fails on its own). */
        const val GATE_SECONDS = 20L
    }
}
