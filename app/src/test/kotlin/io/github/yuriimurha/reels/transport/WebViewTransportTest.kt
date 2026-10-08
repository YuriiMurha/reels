package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.ErrorReplySummary
import io.github.yuriimurha.reels.instagram.web.RawReply
import io.github.yuriimurha.reels.instagram.web.WebHeaders
import io.github.yuriimurha.reels.instagram.web.classifyReply
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
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

    /**
     * R105: every call's fetch has its own AbortController, kept by id, and `window.__igAbort(id)` aborts it; both are defined in
     * the same guarded block as `__igFetch`, so a second injection keeps the controllers of the calls in flight. (The emulator
     * test shows the abort on the wire.)
     */
    @Test
    fun theScriptCanAbortACallById() {
        val script = File("src/main/assets/ig_fetch.js").also { assertTrue(it.isFile, "unit tests must run from the app module directory") }.readText()
        assertTrue(Regex("""signal:\s*controller\.signal""").containsMatchIn(script), "the fetch takes the call's signal")
        assertTrue(Regex("""aborts\[id]\s*=\s*controller""").containsMatchIn(script), "the controller is kept by id")
        assertTrue("window.__igAbort = function (id)" in script, "the abort is exposed")
        val guard = script.indexOf("if (window.__igFetch) return;")
        assertTrue(guard in 0 until script.indexOf("window.__igAbort = function"), "defined once, behind the same guard")
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
