package io.github.yuriimurha.reels.transport

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/**
 * The real repair page ([AndroidRepairPage] with the real `ig_watch.js` from the app's assets) on the emulator, against LOCAL
 * MockWebServers only ([LocalSite]): the allowed origin and every URL loaded are `http://127.0.0.1:<port>` (the guard in
 * [page] refuses anything else), so nothing here can reach Instagram. Every test skips on a physical phone (the smoke suite's
 * [isEmulator] check, as in [AndroidWebPageTest]); none touches the app container or the cookie jar.
 *
 * The fake Saved pages send the site's request themselves, from an inline script, the way the site's own code would: the
 * document-start script is in place before any of the page's scripts run.
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

    // --- 1. Desktop mode ----------------------------------------------------------------------------------------------------

    /**
     * The server sees a desktop browser: a macOS Chrome `user-agent` and, where the WebView can set them, desktop client hints.
     * A WebView that cannot set them never gets a repair page at all (a desktop UA with mobile client hints is a mismatch the
     * app never sends).
     */
    @Test
    fun theDesktopIdentityReachesTheServer() {
        val site = site()
        // What the site's own code sees of the identity and the layout, sent back as a query string.
        val seen = "'ua=' + encodeURIComponent(navigator.userAgent) + '&mobile=' + navigator.userAgentData.mobile + " +
            "'&platform=' + navigator.userAgentData.platform + '&width=' + window.innerWidth + '&dpr=' + window.devicePixelRatio"
        site.route(SAVED) { html(savedPage("fetch('/seen?' + $seen);")) }
        site.route("/seen") { json("{}") }
        val metadata = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
        Log.i(TAG, "USER_AGENT_METADATA supported: $metadata")

        if (!metadata) {
            val refused = onMain { runCatching { AndroidRepairPage(context, allowedOrigin = site.origin) } }
            assertTrue("expected the page to refuse to exist: $refused", refused.exceptionOrNull() is IllegalStateException)
            assertEquals("no request was made", emptyList<RecordedRequest>(), site.requests())
            return
        }

        val watched = onMain { page(site).watch(site.origin + SAVED, NAME, SHORT_MS) }

        assertNull("nothing on this page sends the query", watched)
        val request = site.requestsTo(SAVED).single()
        val userAgent = request.headers["user-agent"].orEmpty()
        Log.i(TAG, "desktop identity: ua=$userAgent; ${CLIENT_HINTS.joinToString("; ") { "$it=${request.headers[it]}" }}")
        assertTrue("a Mac's user agent: $userAgent", "Macintosh" in userAgent)
        assertFalse("not a phone's: $userAgent", "Mobile" in userAgent)
        assertEquals("?0", request.headers["sec-ch-ua-mobile"])
        // The page's own scripts see the same desktop browser.
        val page = site.requestsTo("/seen").single().url
        Log.i(TAG, "the page sees: mobile=${page.queryParameter("mobile")} platform=${page.queryParameter("platform")} " +
            "width=${page.queryParameter("width")} dpr=${page.queryParameter("dpr")}")
        assertEquals(userAgent, page.queryParameter("ua"))
        assertEquals("false", page.queryParameter("mobile"))
        assertEquals("macOS", page.queryParameter("platform"))
    }

    // --- 2. The site's own request, watched -----------------------------------------------------------------------------------

    @Test
    fun aFetchOfTheNamedQueryIsWatched() {
        val token = fakeToken()
        val form = "new URLSearchParams({ ${field(WebGraphQl.Field.FRIENDLY_NAME)}: '$NAME', ${field(WebGraphQl.Field.DOC_ID)}: '42', " +
            "${field(WebGraphQl.Field.DTSG)}: '$token' })"
        assertWatched(token, "fetch('$GRAPHQL', { method: 'POST', body: $form });")
    }

    @Test
    fun anXhrOfTheNamedQueryIsWatched() {
        val token = fakeToken()
        val form = "${WebGraphQl.Field.FRIENDLY_NAME}=$NAME&${WebGraphQl.Field.DOC_ID}=42&${WebGraphQl.Field.DTSG}=$token"
        assertWatched(
            token,
            "var x = new XMLHttpRequest(); x.open('POST', '$GRAPHQL'); " +
                "x.setRequestHeader('content-type', 'application/x-www-form-urlencoded'); x.send('$form');",
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

    /**
     * A Saved page whose [script] sends the named query (doc id 42, with the fake [token] in its form): [AndroidRepairPage.watch]
     * hands back the id, the status and the reply, and the one message that reached the app has no token in it, though the
     * server got the token.
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
        val form = "new URLSearchParams({ ${field(WebGraphQl.Field.FRIENDLY_NAME)}: '$NAME', ${field(WebGraphQl.Field.DOC_ID)}: '42' })"
        site.route(SAVED) { html(savedPage("$posts fetch('$GRAPHQL', { method: 'POST', body: $form });")) }
        site.route(GRAPHQL) { json(REPLY_BODY) }

        val watched = onMain { page(site).watch(site.origin + SAVED, NAME, WATCH_MS) }

        assertEquals("a forgery was taken for the watched query: $heard", "42", watched?.docId)
        assertEquals(REPLY_BODY, watched?.body)
        // Not vacuous: every forgery did reach the app, before the real report.
        assertEquals("the forgeries reached the app first: $heard", 7, heard.size)
        assertTrue("the real report came last: $heard", "\"docId\":\"42\"" in heard.last())
    }

    // --- 4. Where the page lands --------------------------------------------------------------------------------------------

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

    // --- 5. Destroy ---------------------------------------------------------------------------------------------------------

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
        return AndroidRepairPage(context, allowedOrigin = origin).also { page ->
            page.heard = { heard += it }
            pages += page
        }
    }

    /** A Saved page that runs [script] as soon as it is parsed, as the site's own code would send its request. */
    private fun savedPage(script: String): String = "<html><body>saved<script>$script</script></body></html>"

    /** [name] as a JavaScript object key. */
    private fun field(name: String): String = "'$name'"

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

        /** The client hints logged with the desktop identity. */
        val CLIENT_HINTS = listOf("sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform")

        /** A watch that must succeed: long enough for a slow emulator, never reached when the test passes. */
        const val WATCH_MS = 15_000L

        /** A watch that is expected to come back empty. */
        const val SHORT_MS = 3_000L

        /** A watch that only a destroy may end. */
        const val LONG_MS = 60_000L

        /** How long to let a page's last message arrive. */
        const val SETTLE_MS = 500L

        /** How long a server-side gate waits for the other half of a test before it gives up (the test then fails on its own). */
        const val GATE_SECONDS = 20L

        /** JSON escapes, a backslash and real non-ASCII: a body that survives the trip through JSON twice is intact. */
        const val REPLY_BODY = "{\"data\":{\"note\":\"line one\\nline \\\"two\\\" \\\\ café ☃ 😀\"}}"
    }
}
