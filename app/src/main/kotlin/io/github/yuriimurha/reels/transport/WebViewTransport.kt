package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.ErrorReplySummary
import io.github.yuriimurha.reels.instagram.web.InstagramTransport
import io.github.yuriimurha.reels.instagram.web.RawReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * **One page.** The first call (and the first after [reset]) creates the page, loads [homeUrl] and injects [script]; later
 * calls reuse it. At most one call is in flight: a second one is refused with [InstagramException.Transient] (the Pacer
 * serializes real calls, so this only guards misuse).
 *
 * **Where the page landed** decides everything before any call is made. A path under `/accounts/login` is
 * [InstagramException.LoginRequired], under `/challenge` or `/accounts/suspended` [InstagramException.ChallengeRequired]
 * (never with a URL), another host, another scheme or an unreadable URL [InstagramException.Transient]. Login and
 * challenge are remembered: every call fails the same way, without loading or evaluating anything, until [reset]. Another
 * host is not a verdict on the account, so the page is dropped and the next call starts over.
 *
 * **A login bounce from the API** is not seen as one. A page's `fetch(..., {redirect: 'manual'})` reports any redirect as
 * an opaque one, without its target, so it arrives as `RawReply(redirected = true)` and
 * [io.github.yuriimurha.reels.instagram.web.classifyReply] makes it `ChallengeRequired(null)`. Only a page load that lands
 * on `/accounts/login` is [InstagramException.LoginRequired].
 *
 * **Time and failure.** Loading is bounded by [loadTimeoutMs], a call by [callTimeoutMs]; neither blocks a thread (a
 * [CompletableDeferred] under `withTimeout`). A load that fails or times out, a call that times out, and a page that
 * throws drop the page: a stuck page is never reused. A network failure inside the page (`code == -1`) is
 * [InstagramException.Transient] and keeps it. A cancelled call keeps it too; its late reply carries an old id and is ignored.
 *
 * **Threads.** The page is touched on [main] only and all state below is confined to it ([get] and [reset] switch to it).
 * A message is accepted only from the page that is current, only when it parses, and only for the awaited call id.
 *
 * **Debug log** ([log], debug builds only): one line per call, `GET <path, digit runs of 3+ as <n>> -> <code> (<ms> ms)`;
 * `<code>` is `redirect`, `timeout` and so on when there is no status. A non-2xx reply is followed by its
 * [ErrorReplySummary] line. Never a body in a 2xx line, never a header (none is visible here).
 */
class WebViewTransport(
    private val createPage: () -> WebPage,
    private val homeUrl: String,
    private val script: String,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val callTimeoutMs: Long = 30_000,
    private val loadTimeoutMs: Long = 30_000,
    private val log: ((String) -> Unit)? = null,
    /** Only for the debug log's timings; tests pass their virtual one. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : InstagramTransport {
    private val home: URI = URI(homeUrl)

    // Everything below is touched on [main] only.
    private var page: WebPage? = null
    private var scriptInjected = false
    private var blocked: Blocked? = null
    private var busy = false
    private var lastId = 0L
    private var waiting: Waiting? = null

    override suspend fun get(pathAndQuery: String): RawReply {
        // The script prefixes "/", so a leading slash or backslash would make a URL to another host.
        require(pathAndQuery.isNotEmpty() && pathAndQuery[0] != '/' && pathAndQuery[0] != '\\') { "not a path on the Instagram origin" }
        return withContext(main) { logged(pathAndQuery) { call(pathAndQuery) } }
    }

    /** Destroys the page (failing a call in flight with `Transient`) and forgets a login or challenge landing. */
    suspend fun reset() {
        withContext(main) {
            blocked = null
            dropPage()
            waiting?.let { it.reply.complete(null) }
        }
    }

    private suspend fun call(path: String): RawReply {
        if (busy) throw Failed("busy", InstagramException.Transient())
        busy = true
        try {
            blocked?.let { throw Failed(it.reason, it.error()) }
            val current = page ?: openPage()
            val id = ++lastId
            val waiter = Waiting(id, CompletableDeferred())
            waiting = waiter
            try {
                if (!scriptInjected) {
                    current.evaluate(script)
                    scriptInjected = true
                }
                current.evaluate("window.__igFetch && window.__igFetch($id,${JsonPrimitive(path)})")
            } catch (e: Exception) {
                dropPage()
                throw Failed("page error", InstagramException.Transient(e))
            }
            val message = try {
                withTimeout(callTimeoutMs) { waiter.reply.await() }
            } catch (e: TimeoutCancellationException) {
                dropPage()
                throw Failed("timeout", InstagramException.Transient(e))
            }
            // null: reset() destroyed the page under this call.
            return toReply(message ?: throw Failed("page destroyed", InstagramException.Transient()))
        } finally {
            waiting = null
            busy = false
        }
    }

    /** Creates and loads the page, and checks where it landed. Returns it only when it is the Instagram page itself. */
    private suspend fun openPage(): WebPage {
        val created = try {
            createPage().also { fresh ->
                page = fresh
                scriptInjected = false
                // Only the current page is heard: a dropped one may still post.
                fresh.onMessage { raw -> if (page === fresh) accept(raw) }
            }
        } catch (e: Exception) {
            dropPage()
            throw Failed("no page", InstagramException.Transient(e))
        }
        val landed = try {
            withTimeout(loadTimeoutMs) { created.load(homeUrl) }
        } catch (e: TimeoutCancellationException) {
            dropIfCurrent(created)
            throw Failed("load timeout", InstagramException.Transient(e))
        } catch (e: CancellationException) {
            dropIfCurrent(created)
            throw e
        } catch (e: Exception) {
            dropIfCurrent(created)
            throw Failed("load failed", InstagramException.Transient(e))
        }
        // reset() ran while the page was loading and destroyed it: it must not be used, whatever it finished on.
        if (page !== created) throw Failed("page destroyed", InstagramException.Transient())
        val verdict = verdictFor(landed)
        if (verdict != null) {
            dropPage()
            if (verdict is Blocked) blocked = verdict
            throw Failed(verdict.reason, verdict.error())
        }
        return created
    }

    /** Null when [landed] is the Instagram page itself; otherwise what to answer instead. */
    private fun verdictFor(landed: String): Verdict? {
        val uri = try {
            URI(landed)
        } catch (_: URISyntaxException) {
            return Verdict.OffSite
        }
        if (!uri.scheme.equals(home.scheme, ignoreCase = true) || !uri.host.equals(home.host, ignoreCase = true)) return Verdict.OffSite
        val path = uri.path.orEmpty()
        return when {
            path.startsWith("/accounts/login") -> Blocked.LOGIN
            path.startsWith("/challenge") || path.startsWith("/accounts/suspended") -> Blocked.CHALLENGE
            else -> null
        }
    }

    private fun accept(raw: String) {
        val awaited = waiting ?: return
        val message = try {
            JSON.decodeFromString<PageMessage>(raw)
        } catch (_: IllegalArgumentException) {
            return // SerializationException is one: not our message
        }
        if (message.id == awaited.id) awaited.reply.complete(message)
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
        scriptInjected = false
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

    /** The call in flight. [reply] is null when the page was destroyed under it. */
    private class Waiting(val id: Long, val reply: CompletableDeferred<PageMessage?>)

    /** A call that failed: [reason] is for the debug log only, [error] is what the caller gets. */
    private class Failed(val reason: String, val error: InstagramException) : Exception(reason)

    /** What the page's landing means instead of a call. */
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

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val DIGIT_RUN = Regex("\\d{3,}")
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
