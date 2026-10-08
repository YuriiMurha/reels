package io.github.yuriimurha.reels.transport

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuriimurha.reels.NOT_AN_EMULATOR
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.RawReply
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.instagram.web.WebHeaders
import io.github.yuriimurha.reels.isEmulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
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
import java.net.URLDecoder
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
        // The frame did run its attempts (this is not a test of a frame that never loaded). Its own `window.igBridge` is the route
        // the listener's origin rule (exactly the page's origin, never "*") keeps undefined in a frame of another origin, so this
        // is the assertion that pins the rule. How the parent and top routes end is the platform's business (logged).
        Log.i(TAG, "forging frame from another origin reported: ${result.report}")
        assertTrue("the frame reported nothing for its own bridge: ${result.report}", "window.igBridge=" in result.report)
        assertFalse("a frame of another origin got the bridge: ${result.report}", "window.igBridge=sent" in result.report)
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
        assertEquals("window.igBridge=sent", result.report)
        // ...and was dropped.
        assertEquals("the real reply won", REPLY_BODY, result.reply.body)
        assertEquals(200, result.reply.code)
    }

    /**
     * R109: what the main-frame check does NOT stop, pinned as the platform does it. A frame of the same origin can reach its
     * parent's (or the top window's) `igBridge`, and a message posted through that object is credited to the main frame, so its
     * forged reply is accepted and wins over the real one. The trust boundary is therefore the instagram.com origin, not the
     * main frame: any script of that origin can forge a reply or replace `__igFetch`, which is no more than trusting
     * Instagram's own replies. If this test ever fails, the platform got stricter (and the docs can say so).
     */
    @Test
    fun aSameOriginFrameCanSpeakThroughTheParentsBridge() {
        for (route in listOf("parent.igBridge", "top.igBridge")) {
            val site = site()
            val result = callWithAForgingFrame(site, site, listOf(route))

            Log.i(TAG, "same-origin frame through $route reported: ${result.report}, reply body: ${result.reply.body}")
            assertEquals(route, "$route=sent", result.report)
            assertEquals(route, "forged", result.reply.body)
            runBlocking { transport?.reset() } // this round's page; tearDown resets only the last transport
        }
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

    // --- 6. The page's own failures (spec 5) -------------------------------------------------------------------------------

    /** A home page answered 429 is a rate limit: the call fails `RateLimited` and no API request is sent into the limit. */
    @Test
    fun aHomePageAnsweredWithRateLimitingIsRateLimitedAndSendsNoApiRequest() {
        val site = site()
        site.route("/") { MockResponse.Builder().code(429).addHeader("Content-Type", "text/html; charset=utf-8").body("<html>slow down</html>").build() }
        site.route(API) { json(REPLY_BODY) }

        val result = runBlocking { runCatching { transport(site).get(API.removePrefix("/")) } }

        assertTrue("expected RateLimited: $result", result.exceptionOrNull() is InstagramException.RateLimited)
        assertEquals(1, site.requestsTo("/").size)
        assertEquals("no request reached the API path", 0, site.requestsTo(API).size)
    }

    /** A reset (a logout) while the home page loads ends the call at once, not at the 30 s load bound. */
    @Test
    fun aResetWhileTheHomePageLoadsEndsTheCallAtOnce() {
        val site = site()
        val homeAsked = CountDownLatch(1)
        val release = CountDownLatch(1)
        site.route("/") {
            homeAsked.countDown()
            release.await(GATE_SECONDS, TimeUnit.SECONDS) // the server is slow to answer the home page
            html("<html><body>home</body></html>")
        }
        site.route(API) { json(REPLY_BODY) }
        val transport = transport(site)
        try {
            val (result, elapsedMs) = runBlocking {
                val started = System.nanoTime()
                val call = async(Dispatchers.Default) { runCatching { transport.get(API.removePrefix("/")) } }
                assertTrue("the home page was never asked for", homeAsked.await(GATE_SECONDS, TimeUnit.SECONDS))
                transport.reset()
                call.await() to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            }
            assertTrue("expected Transient: $result", result.exceptionOrNull() is InstagramException.Transient)
            assertTrue("the reset ended the load in $elapsedMs ms", elapsedMs < 10_000)
            assertEquals(0, site.requestsTo(API).size)
        } finally {
            release.countDown()
        }
    }

    /** A reply that never comes ends in `Transient` at the call's own bound, and the stuck page is not reused. */
    @Test
    fun aCallWhoseReplyNeverComesTimesOutAndThePageIsDropped() {
        val site = site()
        val release = CountDownLatch(1)
        val apiCalls = AtomicInteger()
        site.route("/") { html("<html><body>home</body></html>") }
        site.route(API) {
            if (apiCalls.incrementAndGet() == 1) {
                release.await(GATE_SECONDS, TimeUnit.SECONDS) // never answered in time
                json("{\"late\":true}")
            } else {
                json(REPLY_BODY)
            }
        }
        val transport = transport(site, callTimeoutMs = 2_000)
        try {
            val (result, elapsedMs) = runBlocking {
                val started = System.nanoTime()
                runCatching { transport.get(API.removePrefix("/")) } to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            }
            assertTrue("expected Transient: $result", result.exceptionOrNull() is InstagramException.Transient)
            assertTrue("timed out after $elapsedMs ms", elapsedMs in 2_000 until 15_000)

            // The next call loads the home page again: the page that timed out was dropped.
            val reply = runBlocking { transport.get(API.removePrefix("/")) }
            assertEquals(REPLY_BODY, reply.body)
            assertEquals(2, site.requestsTo("/").size)
        } finally {
            release.countDown()
        }
    }

    /** A reset (a logout) while a reply is awaited fails the call at once; the late reply is ignored and the next call works. */
    @Test
    fun aResetDuringACallFailsItAtOnceAndItsLateReplyIsIgnored() {
        val site = site()
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val apiCalls = AtomicInteger()
        site.route("/") { html("<html><body>home</body></html>") }
        site.route(API) {
            if (apiCalls.incrementAndGet() == 1) {
                inFlight.countDown()
                release.await(GATE_SECONDS, TimeUnit.SECONDS)
                json("{\"late\":true}")
            } else {
                json(REPLY_BODY)
            }
        }
        val transport = transport(site)
        try {
            val (result, elapsedMs) = runBlocking {
                val started = System.nanoTime()
                val call = async(Dispatchers.Default) { runCatching { transport.get(API.removePrefix("/")) } }
                assertTrue("the call's request never reached the server", inFlight.await(GATE_SECONDS, TimeUnit.SECONDS))
                transport.reset()
                call.await() to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            }
            assertTrue("expected Transient: $result", result.exceptionOrNull() is InstagramException.Transient)
            assertTrue("failed after $elapsedMs ms", elapsedMs < 10_000)
            release.countDown() // the late reply goes to a page that is gone

            val reply = runBlocking { transport.get(API.removePrefix("/")) }
            assertEquals(REPLY_BODY, reply.body)
            assertEquals("a new page after the reset", 2, site.requestsTo("/").size)
        } finally {
            release.countDown()
        }
    }

    // --- 7. The script's request, as the server sees it ----------------------------------------------------------------------

    /** `x-ig-www-claim` is the site's own `sessionStorage['www-claim-v2']` when the site has set it (else `0`, test 1). */
    @Test
    fun theClaimHeaderIsTheSitesOwnStoredClaim() {
        val site = site()
        val claim = listOf("fake", "claim", UUID.randomUUID().toString().take(8)).joinToString("-")
        site.route("/") { html("<html><body><script>sessionStorage.setItem('www-claim-v2', '$claim');</script>home</body></html>") }
        site.route(API) { json(REPLY_BODY) }

        runBlocking { transport(site).get(API.removePrefix("/")) }

        assertEquals(listOf(claim), site.requestsTo(API).map { it.headers["x-ig-www-claim"] })
    }

    /** The site may move itself (`pushState`); the script's path is absolute, so the request still goes to the API path. */
    @Test
    fun aPageThatMovedItselfStillRequestsTheApiPath() {
        val site = site()
        site.route("/") { html("<html><body><script>history.pushState(null, '', '/explore/');</script>home</body></html>") }
        site.route(API) { json(REPLY_BODY) }

        val reply = runBlocking { transport(site).get(API.removePrefix("/")) }

        assertEquals(REPLY_BODY, reply.body)
        assertEquals(1, site.requestsTo(API).size)
        assertEquals(emptyList<RecordedRequest>(), site.requestsTo("/explore/" + API.removePrefix("/")))
    }

    /** A connection the server drops is the page's fetch failing (code -1): `Transient` at once, and the page is kept. */
    @Test
    fun aDroppedConnectionIsTransientAtOnceAndThePageIsKept() {
        val site = site()
        val disconnecting = AtomicBoolean(true)
        site.route("/") { html("<html><body>home</body></html>") }
        site.route(API) {
            if (disconnecting.get()) MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() else json(REPLY_BODY)
        }
        val transport = transport(site)

        val (result, elapsedMs) = runBlocking {
            val started = System.nanoTime()
            runCatching { transport.get(API.removePrefix("/")) } to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        }
        Log.i(TAG, "dropped connection: $result after $elapsedMs ms, ${site.requestsTo(API).size} API request(s) seen")
        assertTrue("expected Transient: $result", result.exceptionOrNull() is InstagramException.Transient)
        assertTrue("failed after $elapsedMs ms", elapsedMs < 10_000)

        disconnecting.set(false)
        val reply = runBlocking { transport.get(API.removePrefix("/")) }
        assertEquals(REPLY_BODY, reply.body)
        assertEquals("no second home load", 1, site.requestsTo("/").size)
    }

    // --- 8. GraphQL: the website's own query, with the page's tokens, which stay in the page -------------------------------

    /**
     * One `graphql` is exactly one POST to the GraphQL path, with the website's form (the page's own `fb_dtsg` and `lsd`, read
     * from the page's HTML here: the test page has no module system) and headers, and the reply comes back intact.
     */
    @Test
    fun graphqlSendsOnePostWithTheFormAndHeaders() {
        val site = site()
        val csrf = fakeToken()
        assertTrue("the cookie jar refused the fake cookie", setCookie(site.origin, "csrftoken=$csrf; Path=/"))
        val tokens = PageTokens()
        site.route("/") { html(tokens.homePage()) }
        site.route(GRAPHQL) { json(REPLY_BODY) }
        val variables = WebGraphQl.savedCollectionsVariables(null)

        val reply = runBlocking { transport(site).graphql(WebGraphQl.SAVED_COLLECTIONS, "123", variables) }

        val calls = site.requestsTo(GRAPHQL)
        assertEquals("exactly one request reached the GraphQL path: $calls", 1, calls.size)
        val call = calls.single()
        assertEquals("POST", call.method)
        val form = formOf(call)
        assertEquals("the website's fields, in its order", WebGraphQl.FORM_FIELDS, form.keys.toList())
        assertEquals(
            mapOf(
                WebGraphQl.Field.DTSG to tokens.dtsg,
                WebGraphQl.Field.LSD to tokens.lsd,
                WebGraphQl.Field.CALLER_CLASS to WebGraphQl.CALLER_CLASS,
                WebGraphQl.Field.FRIENDLY_NAME to "PolarisProfileSavedTabContentQuery",
                WebGraphQl.Field.VARIABLES to variables,
                WebGraphQl.Field.SERVER_TIMESTAMPS to "true",
                WebGraphQl.Field.DOC_ID to "123",
            ),
            form,
        )
        assertTrue(call.headers[WebGraphQl.Header.CONTENT_TYPE].orEmpty().startsWith("application/x-www-form-urlencoded"))
        assertEquals("PolarisProfileSavedTabContentQuery", call.headers[WebGraphQl.Header.FRIENDLY_NAME])
        assertEquals(tokens.lsd, call.headers[WebGraphQl.Header.LSD])
        assertEquals(WebHeaders.APP_ID, call.headers[WebGraphQl.Header.APP_ID])
        assertEquals(WebHeaders.ASBD_ID, call.headers[WebGraphQl.Header.ASBD_ID])
        assertEquals(csrf, call.headers[WebGraphQl.Header.CSRF_TOKEN])
        assertTrue("the page's cookie was sent", "csrftoken=$csrf" in call.headers["Cookie"].orEmpty())
        assertEquals(1, site.requestsTo("/").size)

        assertEquals(200, reply.code)
        assertFalse(reply.redirected)
        assertEquals("application/json; charset=utf-8", reply.contentType)
        assertEquals(REPLY_BODY, reply.body)
    }

    /**
     * A page without both tokens sends nothing: the call is `Transient` with no request made, and the page is dropped (the next
     * call loads the home page again).
     */
    @Test
    fun aPageWithoutTokensSendsNothing() {
        val tokens = PageTokens()
        for ((what, home) in listOf(
            "no tokens" to "<html><body>home</body></html>",
            "no lsd" to tokens.homePage(lsd = false),
            "no dtsg" to tokens.homePage(dtsg = false),
        )) {
            val site = site()
            site.route("/") { html(home) }
            site.route(GRAPHQL) { json(REPLY_BODY) }
            val transport = transport(site)

            val first = runBlocking { runCatching { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, "123", "{}") } }
            assertTrue("$what: expected Transient: $first", first.exceptionOrNull() is InstagramException.Transient)
            val second = runBlocking { runCatching { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, "123", "{}") } }
            assertTrue("$what: expected Transient: $second", second.exceptionOrNull() is InstagramException.Transient)

            assertEquals("$what: no POST was made", emptyList<RecordedRequest>(), site.requests().filter { it.method == "POST" })
            assertEquals("$what: nothing reached the GraphQL path", 0, site.requestsTo(GRAPHQL).size)
            assertEquals("$what: the page was dropped, so the second call loaded the site again", 2, site.requestsTo("/").size)
            runBlocking { transport.reset() } // this round's page; tearDown resets only the last transport
        }
    }

    /** What the page hands the transport carries the reply and never a token, though the server did get both. */
    @Test
    fun theTokensNeverReachTheApp() {
        val site = site()
        val tokens = PageTokens()
        site.route("/") { html(tokens.homePage()) }
        site.route(GRAPHQL) { json(REPLY_BODY) }
        val heard = CopyOnWriteArrayList<String>()

        val reply = runBlocking { transport(site, heard).graphql(WebGraphQl.SAVED_COLLECTIONS, "123", "{}") }

        assertEquals(REPLY_BODY, reply.body)
        // Not vacuous: the tokens were in the page, and went out in the request.
        assertEquals(tokens.dtsg, formOf(site.requestsTo(GRAPHQL).single())["fb_dtsg"])
        assertEquals(tokens.lsd, site.requestsTo(GRAPHQL).single().headers["x-fb-lsd"])
        Log.i(TAG, "messages the page posted: ${heard.size}")
        assertEquals("one message, the reply: $heard", 1, heard.size)
        assertFalse("a token reached the app", heard.any { tokens.dtsg in it || tokens.lsd in it })
    }

    /**
     * The page's own allow-list (spec 6): a name that is not in it is refused in the page with code -3 and nothing is sent. The
     * transport never asks for one (its own list refuses it first), so the test renames the query on its way into the page.
     */
    @Test
    fun anUnknownFriendlyNameIsRefusedInThePage() {
        val site = site()
        val tokens = PageTokens()
        site.route("/") { html(tokens.homePage()) }
        site.route(GRAPHQL) { json(REPLY_BODY) }
        site.route(API) { json(REPLY_BODY) }
        val heard = CopyOnWriteArrayList<String>()
        val transport = transport(site, heard, rewrite = { it.replace("\"PolarisProfileSavedTabContentQuery\"", "\"PolarisSomeOtherQuery\"") })

        val result = runBlocking { runCatching { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, "123", "{}") } }

        assertTrue("expected Transient: $result", result.exceptionOrNull() is InstagramException.Transient)
        assertTrue("the page refused it with -3: $heard", heard.single().contains("\"code\":-3,"))
        assertEquals("nothing reached the GraphQL path", 0, site.requestsTo(GRAPHQL).size)
        // The page is kept: the next call uses it, with no second load of the site.
        assertEquals(REPLY_BODY, runBlocking { transport.get(API.removePrefix("/")) }.body)
        assertEquals(1, site.requestsTo("/").size)
    }

    /**
     * The page's own doc-id check (fix round 1): the server runs whatever persisted query a doc id names, so an id that is not
     * digits only (at most 30) is refused in the page with code -3 and nothing is sent. The transport never asks for one (its
     * own check refuses it first), so the test changes the id on its way into the page.
     */
    @Test
    fun aDocIdThatIsNotDigitsIsRefusedInThePage() {
        val tokens = PageTokens()
        // As they appear in the evaluated call: JSON string literals.
        for (bad in listOf("\"12a\"", "\"\"", "\"" + "1".repeat(31) + "\"", "\"123\\n\"", "\" 123\"")) {
            val site = site()
            site.route("/") { html(tokens.homePage()) }
            site.route(GRAPHQL) { json(REPLY_BODY) }
            val heard = CopyOnWriteArrayList<String>()
            val transport = transport(site, heard, rewrite = { it.replace(",\"123\",", ",$bad,") })

            val result = runBlocking { runCatching { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, "123", "{}") } }

            assertTrue("$bad: expected Transient: $result", result.exceptionOrNull() is InstagramException.Transient)
            assertTrue("$bad: the page refused it with -3: $heard", heard.single().contains("\"code\":-3,"))
            assertEquals("$bad: nothing reached the GraphQL path", 0, site.requestsTo(GRAPHQL).size)
            runBlocking { transport.reset() } // this round's page; tearDown resets only the last transport
        }
    }

    /** Fake `fb_dtsg`/`lsd` values (built from parts, different on every run) and a home page that carries them as the site does. */
    private class PageTokens {
        val dtsg = listOf("fake", "dtsg", UUID.randomUUID().toString().take(8)).joinToString("-")
        val lsd = listOf("fake", "lsd", UUID.randomUUID().toString().take(8)).joinToString("-")

        /** The site's server-rendered module data, in a JSON script element, with the tokens asked for. */
        fun homePage(dtsg: Boolean = true, lsd: Boolean = true): String {
            val modules = listOfNotNull(
                if (dtsg) "[\"DTSGInitialData\",[],{\"token\":\"" + this.dtsg + "\"},258]" else null,
                if (lsd) "[\"LSD\",[],{\"token\":\"" + this.lsd + "\"},323]" else null,
                "[\"SomethingElse\",[],{\"value\":1},1]",
            )
            return "<html><head><script type=\"application/json\">{\"define\":[${modules.joinToString(",")}]}</script></head><body>home</body></html>"
        }
    }

    /** The `application/x-www-form-urlencoded` body of [request], decoded (a key twice fails the test). */
    private fun formOf(request: RecordedRequest): Map<String, String> {
        val pairs = request.body?.utf8().orEmpty().split('&').filter { it.isNotEmpty() }.map { field ->
            val (key, value) = field.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
        assertEquals("each form field once: $pairs", pairs.map { it.first }.distinct(), pairs.map { it.first })
        return pairs.toMap()
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

    /**
     * A frame page that posts forged replies for ids 0..[FORGED_IDS] through each of [routes], once the real call is in flight,
     * and reports `<route>=sent` or `<route>=<error name>` for each, by the route's own name.
     */
    private fun forgingFrame(routes: List<String>): String {
        val posts = routes.joinToString(",") { "['$it', function (message) { $it.postMessage(message); }]" }
        return """
            <html><body><script>
            function forged(id) { return JSON.stringify({id: id, code: 200, contentType: 'text/plain', body: 'forged', redirected: false}); }
            var posts = [$posts];
            fetch('/go').then(function () {
              var out = posts.map(function (route) {
                // Every id the call could have (the first one is 1): a change of the transport's numbering cannot hide a forgery.
                try { for (var id = 0; id <= $FORGED_IDS; id++) route[1](forged(id)); return route[0] + '=sent'; } catch (e) { return route[0] + '=' + e.name; }
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

        fun requests(): List<RecordedRequest> = seen.toList()

        override fun close() = server.close()
    }

    private fun site(): Site = Site().also { sites += it }

    /**
     * The real transport on the real page, pointed at [site]: its origin is the home page and the only allowed sender. With
     * [heard], every message the page hands the transport is also kept there (the transport keeps none it does not wait for).
     * With [rewrite], every script the transport evaluates is changed by it on the way into the page.
     */
    private fun transport(
        site: Site,
        heard: MutableList<String>? = null,
        callTimeoutMs: Long = 30_000,
        rewrite: ((String) -> String)? = null,
    ): WebViewTransport {
        val origin = site.origin
        // The one thing standing between these tests and the real site: nothing but a local address is ever the home page.
        require(origin.startsWith("http://127.0.0.1:")) { "the page tests talk to a local server only: $origin" }
        val script = context.assets.open("ig_fetch.js").bufferedReader().use { it.readText() }
        return WebViewTransport(
            createPage = {
                AndroidWebPage(context, allowedOrigin = origin)
                    .let { page -> if (heard == null) page else Recorded(page, heard) }
                    .let { page -> if (rewrite == null) page else Rewritten(page, rewrite) }
            },
            homeUrl = "$origin/",
            script = script,
            callTimeoutMs = callTimeoutMs,
        ).also { transport = it }
    }

    /** [page], with a copy of every message it delivers put into [heard] first. */
    private class Recorded(private val page: WebPage, private val heard: MutableList<String>) : WebPage by page {
        override fun onMessage(listener: (String) -> Unit) = page.onMessage { raw ->
            heard += raw
            listener(raw)
        }
    }

    /** [page], with every script changed by [rewrite] before it is evaluated. */
    private class Rewritten(private val page: WebPage, private val rewrite: (String) -> String) : WebPage by page {
        override fun evaluate(script: String) = page.evaluate(rewrite(script))
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

        /** Where the page's GraphQL POST goes. */
        const val GRAPHQL = "/" + WebGraphQl.PATH

        /** JSON escapes, a backslash and real non-ASCII (an accent, a symbol, an emoji): a body that survives the trip through JSON twice is intact. */
        const val REPLY_BODY =
            "{\"form_data\":{\"username\":\"user_1\",\"bio\":\"line one\\nline \\\"two\\\" \\\\ café ☃ 😀\"}}"

        /** The forging frames post forged replies for every id from 0 to this one, so the call's own id is certainly among them. */
        const val FORGED_IDS = 16

        /** How long a server-side gate waits for the other half of a test before it gives up (the test then fails on its own). */
        const val GATE_SECONDS = 20L
    }
}
