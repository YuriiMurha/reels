package io.github.yuriimurha.reels.transport

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.yuriimurha.reels.NOT_AN_EMULATOR
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.isEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The real repair page ([AndroidRepairPage] with the real `ig_watch.js` from the app's assets) on the emulator, against LOCAL
 * MockWebServers only ([LocalSite]): the allowed origin and every URL loaded are `http://127.0.0.1:<port>` (the guard in
 * [page] refuses anything else), so nothing here can reach Instagram. Every test skips on a physical phone (the smoke suite's
 * [isEmulator] check, as in [AndroidWebPageTest]); none touches the app container or the cookie jar.
 *
 * The fake Saved pages send the site's request themselves, from an inline script, the way the site's own code would: the
 * document-start script is in place before any of the page's scripts run. Each also reports any error or unhandled rejection
 * that reaches the page's window to `/page-error`, which the watch tests check stays empty: the watching script never throws
 * into the site.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRepairPageTest {
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
    private val sites = mutableListOf<LocalSite>()
    private val pages = mutableListOf<AndroidRepairPage>()

    /** Every message the page's bridge let through to the app (main frame, allowed origin, a string), before it is read. */
    private val heard = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        // The pages first (one may be mid-load), then the servers.
        onMain { pages.forEach { it.destroy() } }
        sites.forEach { it.close() }
    }

    // --- 1. Desktop mode (R10, R9) ------------------------------------------------------------------------------------------

    /**
     * The server sees Chrome on Android with "Desktop site" on ([DesktopSite]): its user agent and its client hints, the
     * high-entropy ones too once the server asks for them (`Accept-CH`), and the page's own scripts see the same. A WebView that
     * cannot set the client hints never gets a repair page at all (a desktop UA with mobile client hints is a mismatch the app
     * never sends).
     */
    @Test
    fun theDesktopIdentityReachesTheServer() {
        val site = site()
        val asked = "['architecture', 'bitness', 'formFactors', 'fullVersionList', 'model', 'platformVersion']"
        site.route(SAVED) {
            html(
                savedPage(
                    "navigator.userAgentData.getHighEntropyValues($asked).then(function (hints) {" +
                        " return fetch('/seen', { method: 'POST', body: JSON.stringify({ ua: navigator.userAgent, hints: hints }) }); });",
                ),
            ).newBuilder().addHeader("Accept-CH", ACCEPT_CH).build()
        }
        site.route("/seen") { json("{}") }
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)
        Log.i(TAG, "USER_AGENT_METADATA and USER_AGENT_METADATA_FORM_FACTORS supported: $supported")

        if (!supported) {
            val refused = onMain { runCatching { AndroidRepairPage(context, allowedOrigin = site.origin) } }
            assertTrue("expected the page to refuse to exist: $refused", refused.exceptionOrNull() is IllegalStateException)
            assertEquals("no request was made", emptyList<RecordedRequest>(), site.requests())
            return
        }
        val desktop = checkNotNull(DesktopSite.of(WebViewCompat.getCurrentWebViewPackage(context)?.versionName))

        val watched = onMain { page(site).watch(site.origin + SAVED, NAME, SHORT_MS) }

        assertNull("nothing on this page sends the query", watched)
        val brands = desktop.brands.joinToString(", ") { "\"${it.name}\";v=\"${it.majorVersion}\"" }
        // The page itself: the user agent and the low-entropy hints every request carries.
        val first = site.requestsTo(SAVED).single()
        Log.i(TAG, "the page's request: ${HINTS.joinToString("; ") { "$it=${first.headers[it]}" }}")
        assertEquals(desktop.userAgent, first.headers["user-agent"])
        assertTrue("Chrome's desktop-site user agent: ${desktop.userAgent}", "(X11; Linux x86_64)" in desktop.userAgent)
        assertEquals(brands, first.headers["sec-ch-ua"])
        assertEquals("?0", first.headers["sec-ch-ua-mobile"])
        assertEquals("\"Linux\"", first.headers["sec-ch-ua-platform"])
        // The request after the server asked for the high-entropy hints.
        val next = site.requestsTo("/seen").single()
        Log.i(TAG, "the request after Accept-CH: ${HINTS.joinToString("; ") { "$it=${next.headers[it]}" }}")
        assertEquals(desktop.userAgent, next.headers["user-agent"])
        assertEquals(brands, next.headers["sec-ch-ua"])
        assertEquals("?0", next.headers["sec-ch-ua-mobile"])
        assertEquals("\"Linux\"", next.headers["sec-ch-ua-platform"])
        assertEquals("\"Desktop\"", next.headers["sec-ch-ua-form-factors"])
        assertEquals("\"64\"", next.headers["sec-ch-ua-bitness"])
        assertEquals("\"x86\"", next.headers["sec-ch-ua-arch"])
        assertEquals("\"\"", next.headers["sec-ch-ua-platform-version"])
        assertEquals("\"\"", next.headers["sec-ch-ua-model"])
        assertEquals(
            desktop.brands.joinToString(", ") { "\"${it.name}\";v=\"${it.fullVersion}\"" },
            next.headers["sec-ch-ua-full-version-list"],
        )
        // The page's own scripts see the same browser.
        val seen = JSONObject(next.body?.utf8().orEmpty())
        val hints = seen.getJSONObject("hints")
        Log.i(TAG, "the page sees: $seen")
        assertEquals(desktop.userAgent, seen.getString("ua"))
        assertFalse(hints.getBoolean("mobile"))
        assertEquals("Linux", hints.getString("platform"))
        assertEquals("", hints.getString("platformVersion"))
        assertEquals("x86", hints.getString("architecture"))
        assertEquals("64", hints.getString("bitness"))
        assertEquals("", hints.getString("model"))
        assertEquals(listOf("Desktop"), strings(hints.getJSONArray("formFactors")))
        assertEquals(desktop.brands.map { it.name to it.majorVersion }, pairs(hints.getJSONArray("brands"), "brand", "version"))
        assertEquals(desktop.brands.map { it.name to it.fullVersion }, pairs(hints.getJSONArray("fullVersionList"), "brand", "version"))
    }

    /** R9: the page is laid out in a 1440 x 900 CSS-pixel window, so a `width=device-width` page is a desktop-wide one. */
    @Test
    fun aDeviceWidthPageIsLaidOutInADesktopWindow() {
        val site = site()
        site.route(SAVED) {
            html(
                savedPage(
                    "window.addEventListener('load', function () { fetch('/seen?width=' + window.innerWidth + '&height=' + window.innerHeight + " +
                        "'&wide=' + matchMedia('(min-width: 1024px)').matches + '&dpr=' + window.devicePixelRatio); });",
                    head = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
                ),
            )
        }
        site.route("/seen") { json("{}") }

        assertNull(onMain { page(site).watch(site.origin + SAVED, NAME, SHORT_MS) })

        val seen = site.requestsTo("/seen").single().url
        Log.i(TAG, "a device-width page sees: ${seen.query}")
        assertEquals("1440", seen.queryParameter("width"))
        assertEquals("true", seen.queryParameter("wide"))
    }

    // --- 2. The site's own request, watched -----------------------------------------------------------------------------------

    @Test
    fun aFetchOfTheNamedQueryIsWatched() {
        val token = fakeToken()
        assertWatched(token, "fetch('$GRAPHQL', { method: 'POST', body: ${searchParams(token)} });")
    }

    @Test
    fun anXhrOfTheNamedQueryIsWatched() {
        val token = fakeToken()
        assertWatched(
            token,
            "var x = new XMLHttpRequest(); x.open('POST', '$GRAPHQL'); " +
                "x.setRequestHeader('content-type', 'application/x-www-form-urlencoded'); x.send('${formText(token)}');",
        )
    }

    @Test
    fun aFormDataBodyIsWatched() {
        val token = fakeToken()
        assertWatched(
            token,
            "var f = new FormData(); f.append('${WebGraphQl.Field.FRIENDLY_NAME}', '$NAME'); f.append('${WebGraphQl.Field.DOC_ID}', '42'); " +
                "f.append('${WebGraphQl.Field.DTSG}', '$token'); fetch('$GRAPHQL', { method: 'POST', body: f });",
        )
    }

    /** `fetch(new Request(...))`: the body is the Request's own, read from a copy before the fetch uses it up. */
    @Test
    fun aRequestObjectIsWatched() {
        val token = fakeToken()
        assertWatched(
            token,
            "fetch(new Request('$GRAPHQL', { method: 'POST', body: '${formText(token)}', " +
                "headers: { 'content-type': 'application/x-www-form-urlencoded' } }));",
        )
    }

    @Test
    fun aUrlObjectIsWatched() {
        val token = fakeToken()
        assertWatched(token, "fetch(new URL('$GRAPHQL', location.href), { method: 'POST', body: ${searchParams(token)} });")
    }

    @Test
    fun aBlobBodyIsWatched() {
        val token = fakeToken()
        assertWatched(
            token,
            "fetch('$GRAPHQL', { method: 'POST', body: new Blob(['${formText(token)}'], { type: 'application/x-www-form-urlencoded' }) });",
        )
    }

    /** An XHR whose reply the browser parses as JSON: watched, its body the site's parsed reply re-serialised. */
    @Test
    fun aJsonXhrIsWatched() {
        val token = fakeToken()
        assertWatched(token, jsonXhr(token))
    }

    /**
     * An XHR that asks for JSON and gets the site's `for (;;);` prefix (so the browser has no JSON for it): the doc id is still
     * watched, with no body, and nothing throws into the site (reading `responseText` there would).
     */
    @Test
    fun aJsonXhrWithoutJsonIsWatchedWithoutABody() {
        val site = site()
        site.route(SAVED) { html(savedPage(jsonXhr(fakeToken()))) }
        site.route(GRAPHQL) { json("for (;;);$REPLY_BODY") }

        val watched = onMain { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) }

        assertEquals("42", watched?.docId)
        assertEquals(200, watched?.code)
        assertNull(watched?.body)
        assertThePageSawNoError(site)
    }

    /**
     * A Saved page whose [script] sends the named query (doc id 42, with the fake [token] in its form): [AndroidRepairPage.watch]
     * hands back the id, the status and the reply, the one message that reached the app has no token in it, though the server
     * got the token, and nothing threw into the page.
     */
    private fun assertWatched(token: String, script: String) {
        val site = site()
        site.route(SAVED) { html(savedPage(script)) }
        site.route(GRAPHQL) { json(REPLY_BODY) }

        val watched = onMain { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) }

        assertNotNull("the site's request was not watched", watched)
        assertEquals("42", watched!!.docId)
        assertEquals(200, watched.code)
        assertEquals(REPLY_BODY, watched.body)
        // Not vacuous: the token was in the request the site made.
        val posted = site.requestsTo(GRAPHQL).single()
        assertEquals("POST", posted.method)
        assertTrue("the site's request carried the token", token in posted.body?.utf8().orEmpty())
        assertEquals("one message, the watched query: $heard", 1, heard.size)
        assertFalse("the token reached the app: $heard", heard.any { token in it })
        assertThePageSawNoError(site)
    }

    // --- 3. Nothing else ---------------------------------------------------------------------------------------------------------

    /** Another query POSTed to the GraphQL path and a GET of an API path are the site's business: the app hears of neither. */
    @Test
    fun otherRequestsAreNeverReported() {
        val site = site()
        val done = CountDownLatch(1)
        val other = "new URLSearchParams({ ${field(WebGraphQl.Field.FRIENDLY_NAME)}: 'PolarisSomeOtherQuery', ${field(WebGraphQl.Field.DOC_ID)}: '43' })"
        site.route(SAVED) {
            html(
                savedPage(
                    "Promise.all([" +
                        "fetch('$GRAPHQL', { method: 'POST', body: $other }).then(function (r) { return r.text(); }), " +
                        "fetch('$OTHER_API').then(function (r) { return r.text(); })" +
                        "]).then(function () { return fetch('/done'); });",
                ),
            )
        }
        site.route(GRAPHQL) { json(REPLY_BODY) }
        site.route(OTHER_API) { json(REPLY_BODY) }
        site.route("/done") {
            done.countDown()
            json("{}")
        }

        val watched = onMain { page(site).watch(site.origin + SAVED, NAME, SHORT_MS) }

        assertNull("another request was taken for the watched one", watched)
        // Not vacuous: both requests were made and both replies were read in the page.
        assertTrue("the page never finished its requests", done.await(GATE_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, site.requestsTo(GRAPHQL).size)
        assertEquals(1, site.requestsTo(OTHER_API).size)
        Thread.sleep(SETTLE_MS) // a report of either would have been posted by now
        assertEquals("the app heard nothing", emptyList<String>(), heard.toList())
    }

    /**
     * Minor 6: only a request that got a reply is reported. The named query by fetch and by XHR, each on a connection the server
     * closes without a reply: the page sees both fail, and the app hears of neither (an XHR ends with status 0, a fetch rejects).
     */
    @Test
    fun aRequestThatGetsNoReplyIsNeverReported() {
        val site = site()
        val done = CountDownLatch(1)
        val outcome = AtomicReference<String>()
        site.route(SAVED) {
            html(
                savedPage(
                    "var a = fetch('$GRAPHQL', { method: 'POST', body: ${searchParams(fakeToken())} })" +
                        ".then(function () { return 'fetch=answered'; }, function () { return 'fetch=failed'; }); " +
                        "var b = new Promise(function (resolve) { var x = new XMLHttpRequest(); x.open('POST', '$GRAPHQL'); " +
                        "x.onloadend = function () { resolve('xhr=' + x.status); }; x.send('${formText(fakeToken())}'); }); " +
                        "Promise.all([a, b]).then(function (out) { return fetch('/done?' + out.join('&')); });",
                ),
            )
        }
        site.route(GRAPHQL) { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        site.route("/done") { request ->
            outcome.set(request.url.query)
            done.countDown()
            json("{}")
        }

        val watched = onMain { page(site).watch(site.origin + SAVED, NAME, SHORT_MS) }

        assertNull("a request without a reply was taken for the watched one", watched)
        assertTrue("the page never saw its requests end", done.await(GATE_SECONDS, TimeUnit.SECONDS))
        assertEquals("both failed in the page", "fetch=failed&xhr=0", outcome.get())
        Thread.sleep(SETTLE_MS)
        assertEquals("the app heard nothing", emptyList<String>(), heard.toList())
    }

    /**
     * The app reads a message only when it is a watched-query report with a doc id of the website's shape. The page's own
     * scripts can post to the bridge (it is the page's origin), so the forgeries below do reach the app, first, and lose.
     */
    @Test
    fun onlyAReportWithADigitsDocIdCounts() {
        val site = site()
        val posts = listOf(
            "{ kind: 'other', docId: '7', code: 200, body: 'forged' }",
            "{ docId: '7', code: 200, body: 'forged' }",
            "{ kind: 'watched', docId: '7a', code: 200, body: 'forged' }",
            "{ kind: 'watched', docId: '', code: 200, body: 'forged' }",
            "{ kind: 'watched', docId: '" + "7".repeat(WebGraphQl.DOC_ID_MAX_DIGITS + 1) + "', code: 200, body: 'forged' }",
            "{ kind: 'watched', docId: 7, code: 200, body: 'forged' }",
        ).joinToString(" ") { "window.igBridge.postMessage(JSON.stringify($it));" }
        site.route(SAVED) { html(savedPage("$posts fetch('$GRAPHQL', { method: 'POST', body: ${searchParams(null)} });")) }
        site.route(GRAPHQL) { json(REPLY_BODY) }

        val watched = onMain { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) }

        assertEquals("a forgery was taken for the watched query: $heard", "42", watched?.docId)
        assertEquals(REPLY_BODY, watched?.body)
        // Not vacuous: every forgery did reach the app, before the real report.
        assertEquals("the forgeries reached the app first: $heard", 7, heard.size)
        assertTrue("the real report came last: $heard", "\"docId\":\"42\"" in heard.last())
    }

    // --- 4. When a watch ends -----------------------------------------------------------------------------------------------

    /** Minor 2: the report ends the watch at once, though the page is still loading (an image the server holds back). */
    @Test
    fun aReportBeforeThePageFinishesWinsAtOnce() {
        val site = site()
        val release = CountDownLatch(1)
        site.route(SAVED) {
            html(savedPage("fetch('$GRAPHQL', { method: 'POST', body: ${searchParams(null)} });", body = "<img src=\"/slow.png\">"))
        }
        site.route(GRAPHQL) { json(REPLY_BODY) }
        site.route("/slow.png") {
            release.await(GATE_SECONDS, TimeUnit.SECONDS) // the page's load event waits for this
            MockResponse.Builder().code(404).build()
        }
        try {
            val (watched, elapsedMs) = timed { onMain { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) } }

            assertEquals("42", watched?.docId)
            assertTrue("the watch waited for the page to finish: $elapsedMs ms", elapsedMs < 5_000)
            assertEquals("the image was asked for and is still held", 1, site.requestsTo("/slow.png").size)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun aLoginLandingIsRepairLandingLogin() {
        assertLanding("/accounts/login/", RepairLanding.Login)
    }

    @Test
    fun aChallengeLandingIsRepairLandingChallenge() {
        assertLanding("/challenge/", RepairLanding.Challenge)
    }

    /** The Saved page redirects to [path]: [AndroidRepairPage.watch] throws [expected], and does not wait for the query. */
    private fun assertLanding(path: String, expected: RepairLanding) {
        val site = site()
        site.route(SAVED) { MockResponse.Builder().code(302).addHeader("Location", path).build() }
        site.route(path) { html("<html><body>not your saved posts</body></html>") }

        val (result, elapsedMs) = timed { onMain { runCatching { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) } } }

        assertEquals("expected $expected: $result", expected, result.exceptionOrNull())
        assertEquals(1, site.requestsTo(path).size)
        assertTrue("it waited $elapsedMs ms", elapsedMs < WATCH_MS)
    }

    /** Minor 3: the Saved page loads, then the site sends itself to the login page: that ends the watch at once. */
    @Test
    fun aLaterLoginPageEndsTheWatch() {
        val site = site()
        site.route(SAVED) { html(savedPage(afterLoad("location.href = '/accounts/login/';"))) }
        site.route("/accounts/login/") { html("<html><body>log in</body></html>") }

        val (result, elapsedMs) = timed { onMain { runCatching { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) } } }

        assertEquals("expected Login: $result", RepairLanding.Login, result.exceptionOrNull())
        assertTrue("it waited $elapsedMs ms", elapsedMs < WATCH_MS)
    }

    /** Minor 3: a later main-frame page that is an HTTP error ends the watch with that status. */
    @Test
    fun aLaterErrorPageEndsTheWatch() {
        val site = site()
        site.route(SAVED) { html(savedPage(afterLoad("location.href = '/broken/';"))) }
        site.route("/broken/") { MockResponse.Builder().code(503).addHeader("Content-Type", "text/html; charset=utf-8").body("<html>down</html>").build() }

        val (result, elapsedMs) = timed { onMain { runCatching { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) } } }

        val error = result.exceptionOrNull()
        assertTrue("expected PageHttpError: $result", error is PageHttpError)
        assertEquals(503, (error as PageHttpError).code)
        assertTrue("it waited $elapsedMs ms", elapsedMs < WATCH_MS)
    }

    /**
     * Minor 3: a page that moves itself to a challenge path without a navigation (`pushState`, nothing finishes loading) is
     * that landing when the watch times out, not a request that never came.
     */
    @Test
    fun aPageThatMovedItselfToAChallengeIsThatLandingAtTheTimeout() {
        val site = site()
        site.route(SAVED) { html(savedPage(afterLoad("history.pushState(null, '', '/challenge/');"))) }

        val (result, elapsedMs) = timed { onMain { runCatching { page(site).watch(site.origin + SAVED, NAME, SHORT_MS) } } }

        Log.i(TAG, "pushState to a challenge: $result after $elapsedMs ms")
        assertEquals("expected Challenge: $result", RepairLanding.Challenge, result.exceptionOrNull())
        assertEquals("nothing but the page was loaded", listOf(SAVED), site.requests().map { it.url.encodedPath }.filter { it != "/favicon.ico" })
    }

    @Test
    fun a429PageIsPageHttpError429() {
        val site = site()
        site.route(SAVED) { MockResponse.Builder().code(429).addHeader("Content-Type", "text/html; charset=utf-8").body("<html>slow down</html>").build() }

        val (result, elapsedMs) = timed { onMain { runCatching { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) } } }

        val error = result.exceptionOrNull()
        assertTrue("expected PageHttpError: $result", error is PageHttpError)
        assertEquals(429, (error as PageHttpError).code)
        assertTrue("it waited $elapsedMs ms", elapsedMs < WATCH_MS)
    }

    /** Minor 4: the page loads nothing off its own origin (another port of the same host is another origin). */
    @Test
    fun aUrlOffTheOriginIsRefused() {
        val site = site()
        val stranger = site()
        stranger.route(SAVED) { html("<html><body>elsewhere</body></html>") }

        val result = onMain { runCatching { page(site).watch(stranger.origin + SAVED, NAME, SHORT_MS) } }

        assertTrue("expected IllegalArgumentException: $result", result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<RecordedRequest>(), stranger.requests())
    }

    // --- 5. Destroy and a dead renderer ---------------------------------------------------------------------------------------

    /** [AndroidRepairPage.destroy] ends a watch at once, while the page loads and while it waits for the query alike. */
    @Test
    fun destroyEndsAWatchAtOnce() {
        for (whileLoading in listOf(false, true)) {
            val site = site()
            val asked = CountDownLatch(1)
            val release = CountDownLatch(1)
            site.route(SAVED) {
                asked.countDown()
                if (whileLoading) release.await(GATE_SECONDS, TimeUnit.SECONDS) // the server is slow to answer the page
                html("<html><body>saved, and no query</body></html>")
            }
            try {
                val (result, elapsedMs) = timed {
                    runBlocking {
                        val page = onMainSuspending { page(site) }
                        val watch = async(Dispatchers.Main) { runCatching { page.watch(site.origin + SAVED, NAME, LONG_MS) } }
                        assertTrue("the page was never asked for", asked.await(GATE_SECONDS, TimeUnit.SECONDS))
                        if (!whileLoading) Thread.sleep(SETTLE_MS) // the page has loaded and waits for the query
                        onMainSuspending { page.destroy() }
                        watch.await()
                    }
                }
                Log.i(TAG, "destroy while loading=$whileLoading: $result after $elapsedMs ms")
                assertTrue("whileLoading=$whileLoading: expected IOException: $result", result.exceptionOrNull() is IOException)
                assertTrue("whileLoading=$whileLoading: it ended after $elapsedMs ms", elapsedMs < 10_000)
            } finally {
                release.countDown()
            }
        }
    }

    /**
     * Minor 5: a renderer that dies (terminated here, as the system would kill it) ends the watch at once, and the page stays
     * dead: a later watch fails at once, without loading anything.
     */
    @Test
    fun aDeadRendererEndsTheWatchAndThePage() {
        val site = site()
        val asked = CountDownLatch(1)
        site.route(SAVED) {
            asked.countDown()
            html("<html><body>saved, and no query</body></html>")
        }
        val (result, elapsedMs) = timed {
            runBlocking {
                val page = onMainSuspending { page(site) }
                val watch = async(Dispatchers.Main) { runCatching { page.watch(site.origin + SAVED, NAME, LONG_MS) } }
                assertTrue("the page was never asked for", asked.await(GATE_SECONDS, TimeUnit.SECONDS))
                Thread.sleep(SETTLE_MS)
                val terminated = onMainSuspending { webViewOf(page).webViewRenderProcess?.terminate() == true }
                assumeTrue("this WebView's renderer cannot be terminated (single process)", terminated)
                watch.await()
            }
        }
        Log.i(TAG, "dead renderer: $result after $elapsedMs ms")
        assertTrue("expected IOException: $result", result.exceptionOrNull() is IOException)
        assertTrue("it ended after $elapsedMs ms", elapsedMs < 10_000)

        val again = onMain { runCatching { pages.last().watch(site.origin + SAVED, NAME, LONG_MS) } }
        assertTrue("expected IOException at once: $again", again.exceptionOrNull() is IOException)
        assertEquals("the dead page loaded nothing more", 1, site.requestsTo(SAVED).size)
    }

    // --- Plumbing ------------------------------------------------------------------------------------------------------------

    private fun site(): LocalSite = LocalSite().also { sites += it }

    /**
     * The real repair page, on the main thread, for [site]: its origin is the only allowed sender and the only origin the
     * watching script runs in. Every message its bridge lets through also goes to [heard].
     */
    private fun page(site: LocalSite): AndroidRepairPage {
        val origin = site.origin
        // The one thing standing between these tests and the real site: nothing but a local address is ever loaded.
        require(origin.startsWith("http://127.0.0.1:")) { "the page tests talk to a local server only: $origin" }
        return AndroidRepairPage(context, allowedOrigin = origin, onRawMessage = { heard += it }).also { pages += it }
    }

    /** The page's WebView, for the one test that kills its renderer. */
    private fun webViewOf(page: AndroidRepairPage): WebView =
        AndroidRepairPage::class.java.getDeclaredField("webView").apply { isAccessible = true }.get(page) as WebView

    /**
     * A Saved page that runs [script] as soon as it is parsed, as the site's own code would send its request, after a script
     * that reports any error or unhandled rejection reaching the window to `/page-error`.
     */
    private fun savedPage(script: String, head: String = "", body: String = ""): String =
        "<html><head>$head<script>" +
            "window.addEventListener('error', function (e) { fetch('/page-error?m=' + encodeURIComponent(String(e.message))); });" +
            "window.addEventListener('unhandledrejection', function (e) { fetch('/page-error?m=' + encodeURIComponent(String(e.reason))); });" +
            "</script></head><body>saved$body<script>$script</script></body></html>"

    /** [script], run a moment after the page has loaded. */
    private fun afterLoad(script: String): String =
        "window.addEventListener('load', function () { setTimeout(function () { $script }, 200); });"

    /** Nothing threw into the page (given a moment for a late report). */
    private fun assertThePageSawNoError(site: LocalSite) {
        Thread.sleep(SETTLE_MS)
        assertEquals("errors reached the page", emptyList<String?>(), site.requestsTo("/page-error").map { it.url.queryParameter("m") })
    }

    /** The named query's form (doc id 42, and the fake [token] when there is one) as a JavaScript `URLSearchParams`. */
    private fun searchParams(token: String?): String =
        "new URLSearchParams({ ${field(WebGraphQl.Field.FRIENDLY_NAME)}: '$NAME', ${field(WebGraphQl.Field.DOC_ID)}: '42'" +
            (token?.let { ", ${field(WebGraphQl.Field.DTSG)}: '$it'" } ?: "") + " })"

    /** The same form as `application/x-www-form-urlencoded` text (the fake values need no escaping). */
    private fun formText(token: String): String =
        "${WebGraphQl.Field.FRIENDLY_NAME}=$NAME&${WebGraphQl.Field.DOC_ID}=42&${WebGraphQl.Field.DTSG}=$token"

    /** The named query by an XHR that asks the browser for a JSON reply. */
    private fun jsonXhr(token: String): String =
        "var x = new XMLHttpRequest(); x.open('POST', '$GRAPHQL'); x.responseType = 'json'; " +
            "x.setRequestHeader('content-type', 'application/x-www-form-urlencoded'); x.send('${formText(token)}');"

    /** [name] as a JavaScript object key. */
    private fun field(name: String): String = "'$name'"

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun pairs(array: JSONArray, first: String, second: String): List<Pair<String, String>> =
        (0 until array.length()).map { array.getJSONObject(it).let { item -> item.getString(first) to item.getString(second) } }

    private fun <T> onMain(block: suspend CoroutineScope.() -> T): T = runBlocking { withContext(Dispatchers.Main, block) }

    private suspend fun <T> onMainSuspending(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Main, block)

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val started = System.nanoTime()
        val result = block()
        return result to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    /** A token value that is plainly not a real one, built from parts (and different on every run). */
    private fun fakeToken(): String = listOf("fake", "dtsg", UUID.randomUUID().toString().take(8)).joinToString("-")

    private fun html(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

    private companion object {
        const val TAG = "AndroidRepairPageTest"

        /** The query the page watches: the only one `ig_watch.js` knows. */
        val NAME = WebGraphQl.SAVED_COLLECTIONS.friendlyName

        /** Where the site posts its queries. */
        const val GRAPHQL = "/" + WebGraphQl.PATH

        /** The fake Saved page (the path is the test's; the app's own comes from Task 5). */
        const val SAVED = "/saved/"

        /** An API path the site GETs. */
        const val OTHER_API = "/api/v1/x"

        /** The client hints the identity test asks the browser for, and those it logs. */
        const val ACCEPT_CH = "Sec-CH-UA-Form-Factors, Sec-CH-UA-Bitness, Sec-CH-UA-Platform, Sec-CH-UA-Full-Version-List, " +
            "Sec-CH-UA-Arch, Sec-CH-UA-Platform-Version, Sec-CH-UA-Model"
        val HINTS = listOf(
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "sec-ch-ua-form-factors", "sec-ch-ua-bitness", "sec-ch-ua-arch",
            "sec-ch-ua-platform-version", "sec-ch-ua-model", "sec-ch-ua-full-version-list",
        )

        /** A watch that must succeed: long enough for a slow emulator, never reached when the test passes. */
        const val WATCH_MS = 15_000L

        /** A watch that is expected to come back empty. */
        const val SHORT_MS = 3_000L

        /** A watch that only a destroy or a dead renderer may end. */
        const val LONG_MS = 60_000L

        /** How long to let a page's last message arrive. */
        const val SETTLE_MS = 500L

        /** How long a server-side gate waits for the other half of a test before it gives up (the test then fails on its own). */
        const val GATE_SECONDS = 20L

        /** JSON escapes, a backslash and real non-ASCII: a body that survives the trip through JSON twice is intact. */
        const val REPLY_BODY = "{\"data\":{\"note\":\"line one\\nline \\\"two\\\" \\\\ café ☃ 😀\"}}"
    }
}
