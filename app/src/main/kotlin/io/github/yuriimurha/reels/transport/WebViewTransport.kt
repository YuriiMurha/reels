package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.ErrorReplySummary
import io.github.yuriimurha.reels.instagram.web.InstagramTransport
import io.github.yuriimurha.reels.instagram.web.RawReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * [InstagramTransport] that runs each API call as one same-origin `fetch()` (`ig_fetch.js`) inside a hidden
 * instagram.com page, so Chromium sends the request with its own TLS stack, headers and cookies. Production transport;
 * [io.github.yuriimurha.reels.instagram.web.OkHttpTransport] is the JVM-test one.
 *
 * **One page.** The first call (and the first after [reset]) creates the page and loads [homeUrl]; later calls reuse it until
 * it has been idle for [IDLE_MS]. At most one call is in flight, the load included: a second one is refused with
 * [InstagramException.Transient] and touches nothing (the Pacer serializes real calls, so this only guards misuse). At most
 * [MAX_PAGES] pages are created per user action (from construction, the last [reset] or the last [allowNewAttempts]), however
 * they end: the site is not loaded over and over. Past that every call is `Transient`, with no page, until one of those.
 *
 * **Idle.** The page is a live single-page app whose own scripts keep sending background requests while it exists, and the
 * process can stay cached for hours. So when a call ends, whatever its outcome, and a page exists, a timer starts; if no call
 * begins within [idleMs] ([IDLE_MS] by default) the page is closed. A call's start cancels the timer, and so do [reset] and
 * a dead renderer. A sync run normally keeps one page for the whole run, because the gaps between its API calls (the Pacer's
 * breaks, a transient backoff of up to 288 s with its jitter, a batch of thumbnail downloads) are normally shorter than 5 min;
 * that is not a promise, and a longer gap closes the page and the next call loads the site again. That clean end of a visit
 * gives back the page limit (a page dropped by a failure never has a timer and still counts), and never touches a remembered
 * login or challenge landing. The cost is one more home-page load per active period.
 *
 * **A home page that is an HTTP error** fails the load: 429 is [InstagramException.RateLimited] (so the caller's cooldown
 * arms with no API request made), any other status [InstagramException.Transient]. The page is dropped either way.
 *
 * **Where the page is** is checked before EVERY call, not only after the load: the site can move itself (`pushState`, a
 * client-side redirect). A path under `/accounts/login` is [InstagramException.LoginRequired], under `/challenge` or
 * `/accounts/suspended` [InstagramException.ChallengeRequired] (never with a URL). Another host, scheme or port (facebook.com,
 * say), an unreadable URL, or no URL at all (the renderer is gone) is [InstagramException.Transient]. Login and challenge
 * are remembered: every call fails the same way, without creating a page or evaluating anything, until [reset]. The others
 * are not a verdict on the account, so the page is dropped and the next call starts over. Nothing is evaluated on a page
 * that failed the check. The script is injected before every fetch (its own guard makes that a no-op when the page still has
 * it, and a navigation may have wiped it), then exactly one fetch is made.
 *
 * **A login bounce from the API** is not seen as one. A page's `fetch(..., {redirect: 'manual'})` reports any redirect as
 * an opaque one, without its target, so it arrives as `RawReply(redirected = true)` and
 * [io.github.yuriimurha.reels.instagram.web.classifyReply] makes it `ChallengeRequired(null)`. Only a page that is on
 * `/accounts/login` is [InstagramException.LoginRequired].
 *
 * **Time and failure.** Loading is bounded by [loadTimeoutMs], a call by [callTimeoutMs]; neither blocks a thread (a
 * [CompletableDeferred] under `withTimeoutOrNull`, which leaves a caller's own deadline alone). A load that fails or times
 * out, a call that times out, and a page that throws all drop the page: a stuck page is never reused. A dead renderer
 * ([WebPage.onGone]) drops it and fails a call in flight at once. A network failure inside the page (`code == -1`) is
 * [InstagramException.Transient] and keeps it.
 *
 * **A cancelled caller** always ends with its own `CancellationException`, never with another exception (one thrown by a
 * cancelled coroutine fails its parent scope, the viewer's `collectLatest` say). Cancelled while the page LOADS (R104), it
 * does not drop the page: the load goes on under `NonCancellable`, still bounded by [loadTimeoutMs] from its start and still
 * ended at once by [reset] or a dead renderer, and its outcome is applied as for any caller (a failure drops the
 * page, a login or challenge landing is remembered, a good page is kept with its idle timer). Only then does the caller get
 * its cancellation. So swipes during a cold load cost one page between them, not one each; the price is that a cancelled
 * caller (and the Pacer's gate it holds) waits for the rest of the load, at most 30 s. A home page answered 429 for a caller
 * that is gone is remembered once (R104a): the NEXT call fails [InstagramException.RateLimited] at once, with no page created
 * and nothing evaluated, so the Pacer still arms its cooldown; [reset] forgets it. Cancelled while it WAITS for a reply
 * (R105), the call keeps the page and aborts its own fetch in it (`window.__igAbort(id)`, see `ig_fetch.js`), so the request
 * ends as an OkHttp call's did and the next call's request is never sent beside it; the late message carries an old id and is
 * ignored.
 *
 * **Threads.** The page is touched on [main] only and all state below is confined to it ([get] and [reset] switch to it, and
 * the idle timer runs there too). A message is accepted only from the page that is current, only when it parses, and only for
 * the awaited call id.
 *
 * **Debug log** ([log], debug builds only): one line per call, `GET <path, digit runs of 3+ as <n>> -> <code> (<ms> ms)`;
 * `<code>` is `redirect`, `timeout` and so on when there is no status. A non-2xx reply is followed by its
 * [ErrorReplySummary] line. Never a body in a 2xx line, never a header (none is visible here). An idle close logs
 * `page closed (idle)`.
 */
class WebViewTransport(
    private val createPage: () -> WebPage,
    private val homeUrl: String,
    private val script: String,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val callTimeoutMs: Long = 30_000,
    private val loadTimeoutMs: Long = 30_000,
    private val idleMs: Long = IDLE_MS,
    private val idleScope: CoroutineScope = CoroutineScope(SupervisorJob() + main),
    private val log: ((String) -> Unit)? = null,
    /** Only for the debug log's timings; tests pass their virtual one. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : InstagramTransport {
    private val home: URI = URI(homeUrl)

    // Everything below is touched on [main] only.
    private var page: WebPage? = null
    private var blocked: Blocked? = null
    private var busy = false
    private var lastId = 0L
    private var waiting: Waiting? = null
    private var pagesCreated = 0
    private var idleTimer: Job? = null

    /** R104a: a cancelled caller's load found a 429 home page; the next call answers it. */
    private var rateLimitedLoad = false

    override suspend fun get(pathAndQuery: String): RawReply {
        // The script prefixes "/", so a slash or a backslash first would make a URL to another host, and so would a tab or a
        // newline in front of one (URL parsing drops them). Nothing but a letter or a digit starts a path of ours.
        require(isPath(pathAndQuery)) { "not a path on the Instagram origin" }
        return withContext(main) { logged(pathAndQuery) { call(pathAndQuery) } }
    }

    /**
     * Destroys the page (failing a call in flight with `Transient`), forgets a login or challenge landing (and a remembered
     * 429, R104a), and gives back the page limit. For a change of session: a logout, a paste, a deleted library, a check that
     * starts after the owner had to act.
     */
    suspend fun reset() {
        withContext(main) {
            blocked = null
            rateLimitedLoad = false
            pagesCreated = 0
            cancelIdleTimer()
            dropPage()
            waiting?.let { it.reply.complete(Answer.Destroyed) }
        }
    }

    /**
     * A new user action begins (a sync run, a session check, a lab tap): the page limit starts counting again, so the three
     * pages one action may create are not used up by an earlier one. This is ALL it does: it never destroys the page, never
     * fails a call in flight, and never forgets a login or challenge landing (only [reset] does, when the session changes).
     */
    suspend fun allowNewAttempts() {
        withContext(main) { pagesCreated = 0 }
    }

    private suspend fun call(path: String): RawReply {
        // First, before the page is created or looked at: a call that arrives while another loads the page must not use it.
        if (busy) throw Failed("busy", InstagramException.Transient())
        busy = true
        // No timer is pending while a call runs: the page is in use from here on.
        cancelIdleTimer()
        try {
            blocked?.let { throw Failed(it.reason, it.error()) }
            if (rateLimitedLoad) {
                // Once: the Pacer arms its cooldown on this answer, and the call after it may load the site again.
                rateLimitedLoad = false
                throw Failed("load http 429 (remembered)", InstagramException.RateLimited())
            }
            val current = page ?: openPage()
            checkLanding(current)
            val id = ++lastId
            val waiter = Waiting(id, CompletableDeferred())
            waiting = waiter
            try {
                current.evaluate(script)
                current.evaluate("window.__igFetch && window.__igFetch($id,${JsonPrimitive(path)})")
            } catch (e: Exception) {
                dropPage()
                throw Failed("page error", InstagramException.Transient(e))
            }
            val answer = try {
                withTimeoutOrNull(callTimeoutMs) { waiter.reply.await() }
            } catch (e: CancellationException) {
                // R105: the caller gave up. Its fetch is aborted in the page (if the page is still the one it went out on), so the
                // request ends now and the next call's is never sent beside it. `evaluate` does not suspend, and its failing must
                // not turn the cancellation into anything else.
                if (page === current) runCatching { current.evaluate("window.__igAbort && window.__igAbort($id)") }
                throw e
            }
            if (answer == null) {
                dropPage()
                throw Failed("timeout", InstagramException.Transient())
            }
            return when (answer) {
                is Answer.Reply -> toReply(answer.message)
                Answer.Destroyed -> throw Failed("page destroyed", InstagramException.Transient())
            }
        } finally {
            waiting = null
            busy = false
            // After the cleanup, so the timer never sees a call that has ended as one in flight.
            armIdleTimer()
        }
    }

    /** After a call, whatever its outcome: when the page is still there, closes it if nothing uses it for [idleMs]. */
    private fun armIdleTimer() {
        val armed = page ?: return
        idleTimer = idleScope.launch(main) {
            delay(idleMs)
            // The page may have been dropped or replaced meanwhile (a dead renderer, say): that is no idle close.
            if (page === armed && !busy) {
                dropPage()
                // A clean end of a visit, not a failure: the next call may load the site again (a page dropped by a failure
                // still counts, and never has a timer). A remembered login or challenge landing is left alone.
                pagesCreated = 0
                log?.invoke("page closed (idle)")
            }
        }
    }

    private fun cancelIdleTimer() {
        idleTimer?.cancel()
        idleTimer = null
    }

    /**
     * Creates and loads the page, and checks how it landed. Returns it only when it is the Instagram page itself.
     *
     * R104: once the load has started it is finished, and its outcome applied, even when the caller is cancelled meanwhile (the
     * load and the checks run under `NonCancellable`, bounded by [loadTimeoutMs] from the load's start). A cancelled caller then
     * gets its cancellation, never the outcome; a 429 is remembered for the next call instead (R104a).
     */
    private suspend fun openPage(): WebPage {
        if (pagesCreated >= MAX_PAGES) throw Failed("page limit", InstagramException.Transient())
        pagesCreated++
        val created = try {
            createPage().also { fresh ->
                page = fresh
                // Only the current page is heard: a dropped one may still post, or die.
                fresh.onMessage { raw -> if (page === fresh) accept(raw) }
                fresh.onGone { if (page === fresh) pageGone() }
            }
        } catch (e: Exception) {
            dropPage()
            throw Failed("no page", InstagramException.Transient(e))
        }
        // The caller's cancellation cannot reach in here: what the load finds is applied whatever happens to the caller. Its
        // own failures come back as a value, so the caller's cancellation (checked below) always wins over them.
        val failure = withContext(NonCancellable) {
            try {
                land(created)
                null
            } catch (e: Failed) {
                e
            }
        }
        if (!currentCoroutineContext().isActive) {
            if (failure?.error is InstagramException.RateLimited) rateLimitedLoad = true
            currentCoroutineContext().ensureActive() // throws the caller's own CancellationException
        }
        failure?.let { throw it }
        return created
    }

    /** Loads [created] and checks where it landed: returns when it is the Instagram page itself, else drops it and throws. */
    private suspend fun land(created: WebPage) {
        val landed = try {
            withTimeoutOrNull(loadTimeoutMs) { created.load(homeUrl) }
        } catch (e: PageHttpError) {
            dropIfCurrent(created)
            throw Failed("load http ${e.code}", if (e.code == 429) InstagramException.RateLimited() else InstagramException.Transient(e))
        } catch (e: Exception) {
            // Not the caller's cancellation (it cannot reach a NonCancellable block): the page's own failure, whatever its type.
            dropIfCurrent(created)
            throw Failed("load failed", InstagramException.Transient(e))
        }
        if (landed == null) {
            dropIfCurrent(created)
            throw Failed("load timeout", InstagramException.Transient())
        }
        // reset() ran while the page was loading and destroyed it: it must not be used, whatever it finished on.
        if (page !== created) throw Failed("page destroyed", InstagramException.Transient())
        verdictFor(landed)?.let { fail(it) }
    }

    /** Before every call: where [current] is now, which is not necessarily where it loaded. */
    private fun checkLanding(current: WebPage) {
        val url = try {
            current.currentUrl()
        } catch (e: Exception) {
            dropPage()
            throw Failed("page error", InstagramException.Transient(e))
        }
        verdictFor(url)?.let { fail(it) }
    }

    /** Null when [url] is the Instagram page itself; otherwise what to answer instead. A null [url] is a page that is gone. */
    private fun verdictFor(url: String?): Verdict? {
        if (url == null) return Verdict.OffSite
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return Verdict.OffSite
        }
        val sameOrigin = uri.scheme.equals(home.scheme, ignoreCase = true) &&
            uri.host.equals(home.host, ignoreCase = true) &&
            effectivePort(uri) == effectivePort(home)
        if (!sameOrigin) return Verdict.OffSite
        val path = uri.path.orEmpty()
        return when {
            path.startsWith("/accounts/login") -> Blocked.LOGIN
            path.startsWith("/challenge") || path.startsWith("/accounts/suspended") -> Blocked.CHALLENGE
            else -> null
        }
    }

    /** The port a URL means: 443 for a bare https one, 80 for http (a page may report `:443` or not). */
    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    /** Drops the page, remembers a login or challenge, and fails the call that found it. */
    private fun fail(verdict: Verdict): Nothing {
        dropPage()
        if (verdict is Blocked) blocked = verdict
        throw Failed(verdict.reason, verdict.error())
    }

    private fun accept(raw: String) {
        val awaited = waiting ?: return
        val message = try {
            JSON.decodeFromString<PageMessage>(raw)
        } catch (_: IllegalArgumentException) {
            return // SerializationException is one: not our message
        }
        if (message.id == awaited.id) awaited.reply.complete(Answer.Reply(message))
    }

    /**
     * The current page's renderer died: the page goes, its idle timer with it, and then a call waiting on it fails now. In that
     * order, as in [reset]: completing the waiter may run the woken call at once (on an immediate dispatcher), and it must find
     * no page left (and no stale timer to cancel a fresh one).
     */
    private fun pageGone() {
        dropPage()
        // A dead page is not held for the idle time: the timer armed for it has nothing left to close.
        cancelIdleTimer()
        waiting?.let { it.reply.complete(Answer.Destroyed) }
    }

    private fun toReply(message: PageMessage): RawReply = when {
        message.redirected -> RawReply(0, null, null, redirected = true)
        // -1 is the page's fetch failing (offline, DNS, reset); anything else outside HTTP's range is no status at all.
        message.code !in 100..599 -> throw Failed("network error", InstagramException.Transient())
        else -> RawReply(message.code, message.contentType, message.body)
    }

    private fun dropIfCurrent(candidate: WebPage) {
        if (page === candidate) dropPage()
    }

    private fun dropPage() {
        val old = page ?: return
        page = null
        // A page that fails to destroy is already unreachable from here; never let that hide the call's own outcome.
        runCatching { old.destroy() }
    }

    private suspend fun logged(path: String, block: suspend () -> RawReply): RawReply {
        val started = timeSource.markNow()
        var outcome = "error"
        var reply: RawReply? = null
        try {
            reply = block()
            outcome = if (reply.redirected) "redirect" else reply.code.toString()
            return reply
        } catch (e: Failed) {
            outcome = e.reason
            throw e.error
        } catch (e: CancellationException) {
            outcome = "cancelled"
            throw e
        } finally {
            log?.let { sink ->
                sink("GET ${path.replace(DIGIT_RUN, "<n>")} -> $outcome (${started.elapsedNow().inWholeMilliseconds} ms)")
                if (reply != null && !reply.redirected && reply.code !in 200..299) {
                    sink(ErrorReplySummary.of(reply.code, reply.contentType, reply.body))
                }
            }
        }
    }

    /** The call in flight. */
    private class Waiting(val id: Long, val reply: CompletableDeferred<Answer>)

    /** What ends a wait: the page's reply, or the page destroyed under the call. (A timeout is no answer at all.) */
    private sealed interface Answer {
        class Reply(val message: PageMessage) : Answer

        object Destroyed : Answer
    }

    /** A call that failed: [reason] is for the debug log only, [error] is what the caller gets. */
    private class Failed(val reason: String, val error: InstagramException) : Exception(reason)

    /** What a page's whereabouts mean instead of a call. */
    private sealed interface Verdict {
        val reason: String
        fun error(): InstagramException

        object OffSite : Verdict {
            override val reason = "unexpected page"
            override fun error(): InstagramException = InstagramException.Transient()
        }
    }

    /** A landing that stays until [reset]: the account needs the owner. */
    private enum class Blocked(override val reason: String) : Verdict {
        LOGIN("login page") {
            override fun error(): InstagramException = InstagramException.LoginRequired()
        },
        CHALLENGE("challenge page") {
            override fun error(): InstagramException = InstagramException.ChallengeRequired(null)
        },
    }

    companion object {
        /** Pages created per user action (R89, R91): the site is loaded at most this often, whatever happens to the pages. */
        const val MAX_PAGES = 3

        /**
         * How long the page may sit between calls before it is closed. Longer than the Pacer's longest break (192 s) and its
         * longest transient backoff (240 s, up to 288 s with its +20 % jitter), so one sync run normally keeps one page: the
         * gaps between a run's API calls are normally shorter than this. A run's thumbnail downloads also sit between API
         * calls, so a longer gap is possible and then simply costs one more home-page load.
         */
        const val IDLE_MS = 300_000L

        private val JSON = Json { ignoreUnknownKeys = true }
        private val DIGIT_RUN = Regex("\\d{3,}")

        /** A letter or a digit first, no tab, carriage return or newline anywhere. */
        private fun isPath(path: String): Boolean =
            path.isNotEmpty() && (path[0] in 'a'..'z' || path[0] in 'A'..'Z' || path[0] in '0'..'9') &&
                path.none { it == '\t' || it == '\r' || it == '\n' }
    }
}

/** What `ig_fetch.js` posts. Its [toString] leaves the body out: nothing of a reply goes into a log line by accident. */
@Serializable
private data class PageMessage(
    val id: Long,
    val code: Int,
    val contentType: String? = null,
    val body: String? = null,
    val redirected: Boolean = false,
) {
    override fun toString() = "PageMessage(id=$id, code=$code, redirected=$redirected, body=${body?.let { "<${it.length} chars>" }})"
}
