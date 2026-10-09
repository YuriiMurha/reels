package io.github.yuriimurha.reels.sync

import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.GraphQlQuery
import io.github.yuriimurha.reels.instagram.web.QueryRepair
import io.github.yuriimurha.reels.instagram.web.RawReply
import io.github.yuriimurha.reels.instagram.web.RepairedQuery
import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import io.github.yuriimurha.reels.transport.PageHttpError
import io.github.yuriimurha.reels.transport.RepairLanding
import io.github.yuriimurha.reels.transport.RepairPage
import io.github.yuriimurha.reels.transport.WatchedQuery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [QueryRepair] through the desktop repair page (spec 2026-10-09 §3.3): the site's own Saved page sends the collections query
 * with its current doc id, and the page watches that one request. Only [io.github.yuriimurha.reels.instagram.web.WebGraphQl.SAVED_COLLECTIONS]
 * can be repaired this way: the Saved page is the one that sends it, and the page watches nothing else.
 *
 * **In this order:**
 * 1. The limit: at most one attempt per [REPAIR_INTERVAL_MS], kept in [settings] (`collections_repair_at`). A last attempt less
 *    than a day ago refuses with [InstagramException.RepairSkipped]: no page. One dated in the future (a clock set back) refuses
 *    too, and is moved back to now first, so the next attempt waits a day from now and never longer.
 * 2. The account's own Saved page, from its [handle] ([WebEndpoints.savedPage], which takes only a handle of Instagram's shape):
 *    no handle, or one of any other shape, refuses with [InstagramException.RepairSkipped] and no page.
 * 3. The attempt is recorded before anything loads, so a repair that crashes half way still counts against the limit.
 * 4. One page, made on [main] (a WebView is main-thread only), loads that Saved page and watches for at most
 *    [REPAIR_TIMEOUT_MS] (the load included); it is destroyed however the watch ends, cancellation included, and a destroy that
 *    throws never hides how the watch ended.
 *
 * **What the watch's ending means:** the site's own reply with a 2xx status is a [RepairedQuery] (the caller parses it and only
 * then learns the id). A 429, as an error page or as the watched reply, is [InstagramException.RateLimited], so the Pacer arms
 * its cooldown; a login page is [InstagramException.LoginRequired]; a challenge page [InstagramException.ChallengeRequired]
 * (no URL). Anything else is [InstagramException.RepairFailed]: another error status (nothing is learned from one), no request
 * seen in time, or a page that failed or could not be made at all (a WebView without desktop mode refuses to construct).
 *
 * **Debug log** ([log], debug builds only): `repair: start`, or `repair: failed (<reason>)`, the reason one of `limit`,
 * `no handle`, `http <code>`, `login page`, `challenge page`, `no query`, `page error`. Never the doc id, the handle, the URL or
 * anything of a reply. `repair: learned new id` is not this class's to say: the sync engine says it once the client has parsed
 * the reply and kept its id (D-I2).
 */
class QueryRepairer(
    private val settings: SettingsStore,
    /** The handle of the account whose session the app holds, or null when it knows none. */
    private val handle: suspend () -> String?,
    private val createPage: () -> RepairPage,
    private val now: () -> Long = System::currentTimeMillis,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val log: ((String) -> Unit)? = null,
) : QueryRepair {
    override suspend fun repair(query: GraphQlQuery): RepairedQuery {
        val now = now()
        val last = settings.collectionsRepairAt()
        if (last != null && last > now) {
            // A clock set back: the limit counts from now, so it never stalls repairs for longer than a day.
            settings.setCollectionsRepairAt(now)
            fail("limit", InstagramException.RepairSkipped("limit"))
        }
        if (last != null && now - last < REPAIR_INTERVAL_MS) fail("limit", InstagramException.RepairSkipped("limit"))
        val url = handle()?.let { WebEndpoints.savedPage(it) } ?: fail("no handle", InstagramException.RepairSkipped("no handle"))
        settings.setCollectionsRepairAt(now)
        log?.invoke("repair: start")
        val outcome = try {
            withContext(main) { watch(url, query) }
        } catch (e: PageHttpError) {
            throw httpError(e.code)
        } catch (e: RepairLanding) {
            when (e) {
                RepairLanding.Login -> fail("login page", InstagramException.LoginRequired())
                RepairLanding.Challenge -> fail("challenge page", InstagramException.ChallengeRequired(null))
            }
        } catch (e: CancellationException) {
            // The caller's cancellation goes on as itself. One from inside the page while the caller is still active is a page
            // that failed, not a cancelled sync.
            currentCoroutineContext().ensureActive()
            fail("page error", InstagramException.RepairFailed("page error"))
        } catch (e: Exception) {
            fail("page error", InstagramException.RepairFailed("page error"))
        }
        val watched = outcome ?: fail("no query", InstagramException.RepairFailed("no query"))
        if (watched.code !in 200..299) throw httpError(watched.code)
        return RepairedQuery(watched.docId, RawReply(watched.code, contentType = null, body = watched.body))
    }

    /** On [main]: one page, made, watched once and destroyed whatever happens. */
    private suspend fun watch(url: String, query: GraphQlQuery): WatchedQuery? {
        val page = createPage()
        try {
            return page.watch(url, query.friendlyName, REPAIR_TIMEOUT_MS)
        } finally {
            // A page that fails to destroy (its renderer gone, say) is unreachable from here anyway; never let that hide the
            // watch's own outcome.
            runCatching { page.destroy() }
        }
    }

    /** A 429 arms the cooldown like any rate limit; another status is a failed repair that learns nothing. */
    private fun httpError(code: Int): InstagramException {
        log?.invoke("repair: failed (http $code)")
        return if (code == 429) InstagramException.RateLimited() else InstagramException.RepairFailed("http $code")
    }

    private fun fail(reason: String, failure: InstagramException): Nothing {
        log?.invoke("repair: failed ($reason)")
        throw failure
    }

    companion object {
        /** At most one repair per this long (spec 2026-10-09 §3.3). */
        const val REPAIR_INTERVAL_MS = 86_400_000L

        /** A repair's whole watch, the page load included. */
        const val REPAIR_TIMEOUT_MS = 45_000L
    }
}
