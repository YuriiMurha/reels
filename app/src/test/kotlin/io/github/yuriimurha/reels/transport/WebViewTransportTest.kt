package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.ErrorReplySummary
import io.github.yuriimurha.reels.instagram.web.RawReply
import io.github.yuriimurha.reels.instagram.web.WebHeaders
import io.github.yuriimurha.reels.instagram.web.classifyReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WebViewTransportTest {
    private val home = "https://www.instagram.com/"

    /** A page that records what it is asked to do and lets the test play the part of the site. */
    private class FakeWebPage(
        private val landing: String = "https://www.instagram.com/",
        private val loadError: Exception? = null,
        /** When set, [load] waits for it, the way a slow page does. */
        private val loadGate: CompletableDeferred<Unit>? = null,
        /** Like the real page: destroying it fails a load in progress. */
        private val destroyFailsTheLoad: Boolean = true,
    ) : WebPage {
        val loaded = mutableListOf<String>()
        val evaluated = mutableListOf<String>()
        var destroyed = false
        private var listener: ((String) -> Unit)? = null

        override suspend fun load(url: String): String {
            loaded += url
            loadGate?.await()
            loadError?.let { throw it }
            return landing
        }

        override fun evaluate(script: String) {
            evaluated += script
        }

        override fun onMessage(listener: (String) -> Unit) {
            this.listener = listener
        }

        override fun destroy() {
            destroyed = true
            if (destroyFailsTheLoad) loadGate?.completeExceptionally(IOException("destroyed"))
        }

        fun post(json: String) = checkNotNull(listener) { "the transport never set a listener" }(json)

        /** The calls this page was asked to make (everything evaluated except the injected script). */
        val fetches: List<String> get() = evaluated.filter { it.startsWith("window.__igFetch") }
    }

    /** Hands the transport its pages in order (then plain healthy ones), and can fail the first creations. */
    private class Pages(vararg pages: FakeWebPage, createErrors: List<Exception> = emptyList()) {
        private val queue = ArrayDeque(pages.toList())
        private val errors = ArrayDeque(createErrors)
        val created = mutableListOf<FakeWebPage>()
        var attempts = 0

        fun create(): WebPage {
            attempts++
            errors.removeFirstOrNull()?.let { throw it }
            return (queue.removeFirstOrNull() ?: FakeWebPage()).also { created += it }
        }
    }

    private fun TestScope.transport(
        pages: Pages,
        callTimeoutMs: Long = 30_000,
        loadTimeoutMs: Long = 30_000,
        log: ((String) -> Unit)? = null,
    ) = WebViewTransport(pages::create, home, SCRIPT, StandardTestDispatcher(testScheduler), callTimeoutMs, loadTimeoutMs, log, testTimeSource)

    private fun TestScope.call(transport: WebViewTransport, path: String): Deferred<Result<RawReply>> =
        async { runCatching { transport.get(path) } }

    private fun reply(
        id: Long,
        code: Int = 200,
        contentType: String? = "application/json",
        body: String? = "{}",
        redirected: Boolean = false,
    ): String = buildJsonObject {
        put("id", id)
        put("code", code)
        put("contentType", contentType)
        put("body", body)
        put("redirected", redirected)
    }.toString()

    /** The exception and its causes. (Coroutine stack-trace recovery wraps a rethrown exception in a copy of itself.) */
    private fun Throwable.causes(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    private fun fetchOf(id: Long, quotedPath: String) = "window.__igFetch && window.__igFetch($id,$quotedPath)"

    @Test
    fun aCallLoadsTheHomePageOnceAndSendsOneFetch() = runTest {
        val pages = Pages()
        val transport = transport(pages)

        val first = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()
        assertEquals(listOf(home), page.loaded)
        // The script goes in once, then exactly one fetch for this call.
        assertEquals(listOf(SCRIPT, fetchOf(1, "\"api/v1/collections/list/\"")), page.evaluated)

        page.post(reply(1, body = """{"status":"ok"}"""))
        val result = first.await().getOrThrow()
        assertEquals(200, result.code)
        assertEquals("application/json", result.contentType)
        assertEquals("""{"status":"ok"}""", result.body)
        assertFalse(result.redirected)

        // A second call reuses the page: no second load, no second script, one more fetch.
        val second = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertEquals(1, pages.created.size)
        assertEquals(listOf(home), page.loaded)
        assertEquals(
            listOf(SCRIPT, fetchOf(1, "\"api/v1/collections/list/\""), fetchOf(2, "\"api/v1/feed/saved/posts/\"")),
            page.evaluated,
        )
        page.post(reply(2))
        assertEquals(200, second.await().getOrThrow().code)
        assertFalse(page.destroyed)
    }

    @Test
    fun repliesAreMatchedByIdAndConcurrentCallsAreRefused() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val a = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()

        // A second call while the first is in flight is refused, and sends nothing.
        val b = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertIs<InstagramException.Transient>(b.await().exceptionOrNull())
        assertEquals(1, page.fetches.size)

        // Replies for another id (a later call, a made-up one) are not the awaited one.
        page.post(reply(2, body = "not for a"))
        page.post(reply(0, body = "not for a"))
        runCurrent()
        assertTrue(a.isActive)
        page.post(reply(1, body = "for a"))
        assertEquals("for a", a.await().getOrThrow().body)

        // The next call has the next id, and a repeat of the old reply does not answer it.
        val c = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertEquals(fetchOf(2, "\"api/v1/feed/saved/posts/\""), page.fetches.last())
        page.post(reply(1, body = "stale"))
        runCurrent()
        assertTrue(c.isActive)
        page.post(reply(2, body = "for c"))
        assertEquals("for c", c.await().getOrThrow().body)
        // And one nobody waits for any more is just dropped.
        page.post(reply(2, body = "late"))
        runCurrent()
    }

    @Test
    fun aPageThatLandsOnLoginMakesNoCall() = runTest {
        val pages = Pages(FakeWebPage(landing = "https://www.instagram.com/accounts/login/?next=%2F"))
        val transport = transport(pages)

        assertIs<InstagramException.LoginRequired>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        val page = pages.created.single()
        assertEquals(emptyList(), page.evaluated)
        assertTrue(page.destroyed)

        // Until reset every call is LoginRequired: the page is not loaded again and nothing is evaluated.
        assertIs<InstagramException.LoginRequired>(call(transport, "api/v1/feed/saved/posts/").await().exceptionOrNull())
        assertEquals(1, pages.attempts)
        assertEquals(emptyList(), page.evaluated)

        // After a reset (the owner logged in) the next call loads a fresh page and goes through.
        transport.reset()
        val after = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(1))
        assertEquals(200, after.await().getOrThrow().code)
    }

    @Test
    fun aPageThatLandsOnAChallengeMakesNoCall() = runTest {
        for (landing in listOf(
            "https://www.instagram.com/challenge/",
            "https://www.instagram.com/challenge/action/AXabc/?next=%2F",
            "https://www.instagram.com/accounts/suspended/",
        )) {
            val pages = Pages(FakeWebPage(landing = landing))
            val transport = transport(pages)

            val error = assertIs<InstagramException.ChallengeRequired>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
            // The landing URL is never kept: it carries a nonce.
            assertNull(error.challengeUrl)
            assertEquals(emptyList(), pages.created.single().evaluated)
            assertIs<InstagramException.ChallengeRequired>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
            assertEquals(1, pages.attempts, landing)
        }
    }

    @Test
    fun aPageThatLandsOnAnotherHostIsTransientAndIsDropped() = runTest {
        for (landing in listOf(
            "https://example.invalid/",
            "https://example.invalid/accounts/login/",
            "https://www.instagram.com.evil.example/accounts/login/",
            "http://www.instagram.com/",
            "not a url at all ::",
        )) {
            val pages = Pages(FakeWebPage(landing = landing))
            val transport = transport(pages)

            assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull(), landing)
            val stray = pages.created.single()
            assertEquals(emptyList(), stray.evaluated, landing)
            assertTrue(stray.destroyed, landing)

            // Not a verdict on the account: the next call starts over with a fresh page.
            val next = call(transport, "api/v1/collections/list/")
            runCurrent()
            assertEquals(2, pages.created.size, landing)
            pages.created.last().post(reply(1))
            assertEquals(200, next.await().getOrThrow().code)
        }
    }

    @Test
    fun aRedirectMessageBecomesARedirectedReply() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = call(transport, "api/v1/media/3100000000000000001/info/")
        runCurrent()
        pages.created.single().post("""{"id":1,"code":0,"contentType":null,"body":null,"redirected":true}""")

        val result = pending.await().getOrThrow()
        assertTrue(result.redirected)
        assertEquals(0, result.code)
        assertNull(result.body)
        // Through this transport a login bounce is just an opaque redirect: a challenge with no URL.
        val failure = assertIs<InstagramException.ChallengeRequired>(classifyReply(result))
        assertNull(failure.challengeUrl)
    }

    @Test
    fun aNetworkFailureMessageIsTransient() = runTest {
        val pages = Pages()
        val transport = transport(pages)

        val first = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()
        page.post("""{"id":1,"code":-1,"contentType":null,"body":null,"redirected":false}""")
        assertIs<InstagramException.Transient>(first.await().exceptionOrNull())

        // Not a status either: an unreadable code is never handed to the classifier.
        val second = call(transport, "api/v1/collections/list/")
        runCurrent()
        page.post(reply(2, code = 0))
        assertIs<InstagramException.Transient>(second.await().exceptionOrNull())

        // The page itself is fine and is kept.
        val third = call(transport, "api/v1/collections/list/")
        runCurrent()
        page.post(reply(3))
        assertEquals(200, third.await().getOrThrow().code)
        assertEquals(1, pages.created.size)
        assertFalse(page.destroyed)
    }

    @Test
    fun aMalformedMessageIsIgnored() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()

        for (junk in listOf("not json", "[]", "null", "", """{"id":1}""", """{"id":1,"code":"x"}""", """{"code":200,"body":"{}"}""")) {
            page.post(junk)
        }
        runCurrent()
        // None of it answered the call: it is still waiting, and still times out.
        assertTrue(pending.isActive)
        advanceTimeBy(30_000)
        runCurrent()
        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
    }

    @Test
    fun theCallTimesOutAndDropsThePage() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = call(transport, "api/v1/collections/list/")
        runCurrent()
        val stuck = pages.created.single()

        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(pending.isActive)
        advanceTimeBy(1)
        runCurrent()
        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
        assertEquals(30_000, currentTime)
        // A stuck page is not reused.
        assertTrue(stuck.destroyed)

        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        val fresh = pages.created.last()
        assertEquals(listOf(SCRIPT, fetchOf(2, "\"api/v1/collections/list/\"")), fresh.evaluated)
        // Whatever the dropped page still says is not heard, even for an id that is now awaited.
        stuck.post(reply(2, body = "from the dropped page"))
        runCurrent()
        assertTrue(next.isActive)
        fresh.post(reply(2, body = "from the new page"))
        assertEquals("from the new page", next.await().getOrThrow().body)
    }

    @Test
    fun destroyCancelsTheCallInFlight() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()

        transport.reset()
        runCurrent()
        // Answered at once, not at the timeout.
        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
        assertEquals(0, currentTime)
        assertTrue(page.destroyed)

        // The next call creates and loads a new page.
        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(2))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun destroyDuringThePageLoadFailsTheCallAtOnce() = runTest {
        for (destroyFailsTheLoad in listOf(true, false)) {
            val gate = CompletableDeferred<Unit>()
            val pages = Pages(FakeWebPage(loadGate = gate, destroyFailsTheLoad = destroyFailsTheLoad))
            val transport = transport(pages)
            val pending = call(transport, "api/v1/collections/list/")
            runCurrent()
            val page = pages.created.single()
            assertEquals(listOf(home), page.loaded)

            transport.reset()
            // A page that loads anyway after it was destroyed is not used.
            gate.complete(Unit)
            runCurrent()
            assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
            assertEquals(emptyList(), page.evaluated)
            assertTrue(page.destroyed)
            assertEquals(0, currentTime)
        }
    }

    @Test
    fun aPageThatCannotBeCreatedFailsTransient() = runTest {
        val pages = Pages(createErrors = listOf(IllegalStateException("no WebView provider")))
        val transport = transport(pages)

        val error = assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertTrue(error.causes().any { it is IllegalStateException }, "the Transient keeps what went wrong")
        assertTrue(pages.created.isEmpty())

        // Nothing is remembered: the next call tries to create the page again.
        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.attempts)
        pages.created.single().post(reply(1))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun aPageThatFailsToLoadFailsTransientAndIsDropped() = runTest {
        val pages = Pages(FakeWebPage(loadError = IOException("net::ERR_INTERNET_DISCONNECTED")))
        val transport = transport(pages)

        val error = assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertTrue(error.causes().any { it is IOException }, "the Transient keeps what went wrong")
        val broken = pages.created.single()
        assertTrue(broken.destroyed)
        assertEquals(emptyList(), broken.evaluated)

        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(1))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun aPageThatNeverFinishesLoadingTimesOutTransient() = runTest {
        val pages = Pages(FakeWebPage(loadGate = CompletableDeferred()))
        val transport = transport(pages, loadTimeoutMs = 20_000)
        val pending = call(transport, "api/v1/collections/list/")
        runCurrent()

        advanceTimeBy(19_999)
        runCurrent()
        assertTrue(pending.isActive)
        advanceTimeBy(1)
        runCurrent()
        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
        assertEquals(20_000, currentTime)
        val slow = pages.created.single()
        assertTrue(slow.destroyed)
        assertEquals(emptyList(), slow.evaluated)
    }

    @Test
    fun aCancelledCallFreesTheTransportAndItsLateReplyIsIgnored() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val abandoned = launch { transport.get("api/v1/collections/list/") }
        runCurrent()
        val page = pages.created.single()
        abandoned.cancel()
        runCurrent()

        val next = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        // Same page: a cancelled call is not a stuck page. The new call has a new id.
        assertEquals(1, pages.created.size)
        assertEquals(fetchOf(2, "\"api/v1/feed/saved/posts/\""), page.fetches.last())
        page.post(reply(1, body = "late reply of the cancelled call"))
        runCurrent()
        assertTrue(next.isActive)
        page.post(reply(2, body = "answer"))
        assertEquals("answer", next.await().getOrThrow().body)
    }

    @Test
    fun thePathIsJsonQuotedInTheCall() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = call(transport, "api/v1/x/?q=\"</script>\\")
        runCurrent()
        assertEquals(fetchOf(1, "\"api/v1/x/?q=\\\"</script>\\\\\""), pages.created.single().fetches.single())
        pages.created.single().post(reply(1))
        pending.await()
    }

    @Test
    fun aPathThatCouldLeaveTheOriginIsRefusedBeforeAnyPageIsCreated() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        // The script prefixes "/", so these would become protocol-relative URLs to another host.
        for (path in listOf("", "/api/v1/x/", "//example.invalid/x", "\\example.invalid\\x")) {
            assertFailsWith<IllegalArgumentException>(path) { transport.get(path) }
        }
        assertEquals(0, pages.attempts)
    }

    @Test
    fun theScriptsHeadersMatchTheConstants() {
        val script = File("src/main/assets/ig_fetch.js").also { assertTrue(it.isFile, "unit tests must run from the app module directory") }.readText()
        assertTrue("'x-ig-app-id': '${WebHeaders.APP_ID}'" in script, "x-ig-app-id")
        assertTrue("'x-asbd-id': '${WebHeaders.ASBD_ID}'" in script, "x-asbd-id")
        assertTrue("'x-requested-with': 'XMLHttpRequest'" in script, "x-requested-with")
        // One GET, same origin, never following a redirect.
        assertTrue("method: 'GET'" in script)
        assertTrue("credentials: 'same-origin'" in script)
        assertTrue("redirect: 'manual'" in script)
        // Nothing but the reply goes to Kotlin: a cookie, the CSRF token or the www-claim must never be in a message.
        val posts = Regex("""igBridge\.postMessage\(JSON\.stringify\(\{(.*?)\}\)\)""").findAll(script).map { it.groupValues[1] }.toList()
        assertEquals(4, posts.size, "the script posts a message in four places")
        for (fields in posts) {
            val keys = Regex("""(\w+):""").findAll(fields).map { it.groupValues[1] }.toSet()
            assertEquals(setOf("id", "code", "contentType", "body", "redirected"), keys)
        }
        assertEquals(4, Regex("""igBridge\.postMessage\(""").findAll(script).count(), "every postMessage is one of those")
    }

    @Test
    fun theDebugLogShowsPathStatusAndTimeButNoBody() = runTest {
        val lines = mutableListOf<String>()
        val pages = Pages()
        val transport = transport(pages, log = lines::add)
        val pending = call(transport, "api/v1/feed/collection/17900000000000002/posts/?max_id=QVFE_cursor_9&n=12")
        runCurrent()
        advanceTimeBy(120)
        pages.created.single().post(
            reply(1, body = """{"items":[{"pk":"3100000000000000001","caption":{"text":"a private caption"}}],"status":"ok"}"""),
        )
        pending.await().getOrThrow()

        // Digit runs of three or more are hidden; shorter ones stay. Nothing of the body, and no summary for a 2xx.
        assertEquals(listOf("GET api/v1/feed/collection/<n>/posts/?max_id=QVFE_cursor_9&n=12 -> 200 (120 ms)"), lines)
    }

    @Test
    fun theLogShowsTheRedactedSummaryForErrors() = runTest {
        val lines = mutableListOf<String>()
        val pages = Pages()
        val transport = transport(pages, log = lines::add)
        val body = """{"message":"Please wait a few minutes before you try again.","status":"fail","checkpoint_url":"https://www.instagram.com/challenge/AbCd/"}"""
        val pending = call(transport, "api/v1/media/3100000000000000001/info/")
        runCurrent()
        advanceTimeBy(40)
        pages.created.single().post(reply(1, code = 429, body = body))
        pending.await().getOrThrow()

        assertEquals(
            listOf(
                "GET api/v1/media/<n>/info/ -> 429 (40 ms)",
                ErrorReplySummary.of(429, "application/json", body),
            ),
            lines,
        )
        // The summary is the allow-listed one: key names at most, never the URL or any other value from the body.
        assertFalse(lines.any { "AbCd" in it || "instagram.com" in it || "http" in it })
        assertTrue(lines.last().startsWith("<-- 429 reply: status=fail"))
    }

    @Test
    fun theLogNamesWhatHappenedWhenThereIsNoStatus() = runTest {
        val lines = mutableListOf<String>()
        val pages = Pages()
        val transport = transport(pages, log = lines::add)

        val redirected = call(transport, "api/v1/collections/list/")
        runCurrent()
        pages.created.single().post(reply(1, code = 0, contentType = null, body = null, redirected = true))
        redirected.await().getOrThrow()

        val timedOut = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertIs<InstagramException.Transient>(timedOut.await().exceptionOrNull())

        // A redirect has no status and no body to summarise; a timeout has neither.
        assertEquals(
            listOf(
                "GET api/v1/collections/list/ -> redirect (0 ms)",
                "GET api/v1/feed/saved/posts/ -> timeout (30000 ms)",
            ),
            lines,
        )
    }

    private companion object {
        /** Stands in for ig_fetch.js: the transport only passes it on. */
        const val SCRIPT = "/* ig_fetch stand-in */ window.standIn = 1;"
    }
}
