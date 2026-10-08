package io.github.yuriimurha.reels.sync

import androidx.room.withTransaction
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.CollectionMediaEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.db.SyncCursorEntity
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.db.ThumbnailTarget
import io.github.yuriimurha.reels.data.media.CdnRateLimited
import io.github.yuriimurha.reels.data.media.MediaEviction
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerRefusal
import io.github.yuriimurha.reels.sync.pacing.retryTransient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** Runs one sync (spec 7.2): session check, collection list, scope walks, reconcile, thumbnails. */
class SyncEngine(
    private val client: InstagramClient,
    private val pacer: Pacer,
    private val db: ReelsDatabase,
    private val fetcher: MediaFetcher,
    private val thumbnails: ThumbnailStore,
    private val signals: SessionSignals = SessionSignals.None,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
    /** Told which items a reconcile removed, so their cached videos go with their thumbnails (spec 8.3). */
    private val eviction: MediaEviction = MediaEviction { },
    /**
     * R82: asked from inside the Pacer's gate right before every request (the session check included), with the epoch the run
     * started under. Anything but [RunSession.USABLE] stops the run with nothing more sent: a challenge or an expiry another
     * lane stored (the viewer, the lab, Check now), a logout, a paste, or a login as another account. The fake backend has no
     * session: the default lets every request through.
     */
    private val sessionUsable: suspend (epoch: Int) -> RunSession = { RunSession.USABLE },
    /** R84: the account this library belongs to. A run under another account stops before it writes anything. */
    private val libraryAccount: LibraryAccount,
) {
    companion object {
        /** P7: a FULL reconcile removing at least this many items AND more than half of those that existed before the run is refused. */
        internal const val RECONCILE_GUARD_MIN_ITEMS = 20

        /** R84: the `lastError` (and the Sync screen's banner) of a run under another account than the library's. */
        internal const val ANOTHER_ACCOUNT = "This library belongs to another Instagram account. Delete library to switch."
    }

    private val mediaDao = db.mediaDao()
    private val collectionDao = db.collectionDao()
    private val syncDao = db.syncDao()

    /**
     * Executes or resumes run [runId]. Its final status is always written to `sync_run` before this returns:
     * expected failures map to a status, and any unexpected error pauses the run with a `lastError` that names
     * only the exception class. Only cancellation propagates (after the run is left PAUSED, "Cancelled").
     */
    suspend fun run(runId: Long) {
        val stored = checkNotNull(syncDao.run(runId)) { "No sync run $runId" }
        val progress = Progress(
            stored.copy(status = SyncStatus.RUNNING, lastError = null, finishedAt = null, collectionsDone = 0),
            pacer.newRun(),
            epoch = 0, // replaced below, inside the try
        )
        progress.save()
        var epoch = 0
        try {
            // The session this run starts under. Every signal below carries it, so one that outlives a logout or a paste is
            // ignored. Asked INSIDE the try: a session layer that cannot answer (no WebView provider while it is being updated)
            // ends the run PAUSED like any other unexpected error, instead of leaving the row RUNNING.
            epoch = signals.epoch()
            progress.epoch = epoch
            progress.phase("Checking session")
            val account = call(progress) { client.currentUser() }
            notifySession { signals.sessionOk(account.username, epoch) }
            ensureLibraryAccount(account.pk)
            progress.phase("Listing collections")
            val collections = fetchCollections(progress)
            val scopes = listOf(ALL_SAVED_ID to "All Saved") +
                if (client.reportsSavedCollectionIds) emptyList() else collections.map { it.id to it.name }
            progress.update { it.copy(collectionsTotal = scopes.size) }
            val knownCollections = collections.map { it.id }.toSet()
            for ((scope, label) in scopes) walkScope(progress, scope, label, knownCollections)
            progress.finish(SyncStatus.DONE, null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { progress.finish(SyncStatus.PAUSED, "Cancelled") }
            throw e
        } catch (e: SessionNotUsable) {
            // R82: the session stopped being usable under the run and nothing more was sent. No signal: the session layer
            // already knows, and challengeRequired(null, ...) would wipe the challenge URL it stored.
            if (e.challenge) {
                progress.finish(SyncStatus.STOPPED_CHALLENGE, "Instagram wants verification")
            } else {
                progress.finish(SyncStatus.STOPPED_LOGIN, "Session expired")
            }
        } catch (e: InstagramException.ChallengeRequired) {
            progress.finish(SyncStatus.STOPPED_CHALLENGE, "Instagram wants verification")
            notifySession { signals.challengeRequired(e.challengeUrl, epoch) }
        } catch (e: InstagramException.LoginRequired) {
            progress.finish(SyncStatus.STOPPED_LOGIN, "Session expired")
            notifySession { signals.loginRequired(epoch) }
        } catch (e: InstagramException.RateLimited) {
            progress.finish(SyncStatus.STOPPED_RATE_LIMIT, "Instagram is limiting requests")
        } catch (e: PacerRefusal.CoolingDown) {
            progress.finish(SyncStatus.STOPPED_RATE_LIMIT, "Cooling down")
        } catch (e: InstagramException.ShapeChanged) {
            progress.finish(SyncStatus.STOPPED_SHAPE, "Adapter needs repair: ${e.fieldPath}")
        } catch (e: AnotherAccount) {
            progress.finish(SyncStatus.STOPPED_SHAPE, ANOTHER_ACCOUNT)
        } catch (e: InstagramException.Transient) {
            progress.finish(SyncStatus.PAUSED, "Network problem, try again later")
        } catch (e: PacerRefusal.RunBudgetReached) {
            progress.finish(SyncStatus.PAUSED, "Run budget reached, tap Resume")
        } catch (e: PacerRefusal.DailyBudgetReached) {
            progress.finish(SyncStatus.PAUSED, "24-hour budget reached")
        } catch (e: Exception) {
            // Last resort: never strand the run as RUNNING. The message is dropped on purpose (it may hold session data).
            progress.finish(SyncStatus.PAUSED, "Unexpected error: ${e::class.simpleName ?: "Exception"}")
        }
    }

    /**
     * Tells the session layer what the run learned: a successful session check, or a stop whose status is already written.
     * A failing receiver must not change that status or end the run, and must not escape; cancellation still propagates.
     */
    private suspend fun notifySession(signal: suspend () -> Unit) {
        try {
            signal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Intentionally ignored: the run is already STOPPED_*; the owner sees it on the Sync screen.
        }
    }

    /** One paced request, retried after a transient failure. Every attempt first passes [ensureSessionUsable], inside the gate. */
    private suspend fun <T> call(progress: Progress, request: suspend () -> T): T =
        retryTransient(random) {
            pacer.sync(progress.budget, precondition = { ensureSessionUsable(progress.epoch) }, request = request)
        }

    private suspend fun ensureSessionUsable(epoch: Int) {
        when (sessionUsable(epoch)) {
            RunSession.USABLE -> Unit
            RunSession.CHALLENGE -> throw SessionNotUsable(challenge = true)
            RunSession.NOT_USABLE -> throw SessionNotUsable(challenge = false)
        }
    }

    /** [sessionUsable] said no: thrown inside the Pacer's gate before anything is sent or counted, mapped to a stop in [run]. */
    private class SessionNotUsable(val challenge: Boolean) : Exception()

    /**
     * R84: one library, one account. The first run whose session check succeeds remembers the account's [pk] (only while none
     * is stored); a run under any other account stops right here, before the collection list is even requested, so nothing of
     * that account's feed is written and no reconcile can count it. Delete library forgets the account, to switch.
     */
    private suspend fun ensureLibraryAccount(pk: String) {
        when (libraryAccount.pk()) {
            null -> libraryAccount.remember(pk)
            pk -> Unit
            else -> throw AnotherAccount()
        }
    }

    /** The session belongs to another account than the library ([ensureLibraryAccount]). */
    private class AnotherAccount : Exception()

    private suspend fun fetchCollections(progress: Progress): List<CollectionEntity> {
        val remote = mutableListOf<RemoteCollection>()
        var cursor: String? = null
        do {
            val from = cursor
            val page = call(progress) { client.collections(from) }
            remote += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        // An empty list over a library that has collections is a broken answer, not an account that deleted them all: marking
        // them removed would hide every collection. Thrown before the transaction, so nothing has been touched.
        if (remote.isEmpty() && collectionDao.liveCollectionCount() > 0) throw InstagramException.ShapeChanged("empty collection list")
        val live = remote.mapIndexed { index, c -> CollectionEntity(c.id, c.name, c.coverMediaPk, position = index) }
        db.withTransaction {
            collectionDao.upsert(live + CollectionEntity(ALL_SAVED_ID, "All Saved", coverPk = null, position = -1))
            collectionDao.markRemovedExcept(live.map { it.id }, now())
        }
        return live
    }

    private suspend fun walkScope(progress: Progress, scope: String, label: String, knownCollections: Set<String>) {
        val resumed = syncDao.cursor(progress.run.id, scope)
        var cursor = resumed ?: SyncCursorEntity(
            runId = progress.run.id,
            scope = scope,
            nextCursor = null,
            walkBase = SortKeys.walkBase(collectionDao.maxSortKey(scope)),
            walkIndex = 0,
            done = false,
        ).also { syncDao.upsertCursor(it) }
        // A page is committed before its thumbnails are cached, so a run that died in between never revisits that page.
        if (resumed != null) {
            val left = mediaDao.withoutThumbnailSeenIn(scope, progress.run.id)
            cacheThumbnails(progress, left, countFailures = false)
        }
        if (!cursor.done) progress.phase("Syncing $label")
        while (!cursor.done) {
            val from = cursor.nextCursor
            val page = call(progress) { client.savedMedia(scope.takeUnless { it == ALL_SAVED_ID }, from) }
            val current = cursor
            val outcome = db.withTransaction { applyPage(progress, scope, current, page, knownCollections) }
            cursor = outcome.cursor
            outcome.removedPks.forEach(thumbnails::delete)
            evictVideos(outcome.removedPks)
            cacheThumbnails(progress, outcome.needThumbnails.map { ThumbnailTarget(it.pk, it.thumbnailUrl) })
        }
        progress.update { it.copy(collectionsDone = it.collectionsDone + 1) }
    }

    /**
     * Housekeeping after the reconcile has been committed: a cache that cannot be written (a full disk) must not stop the run,
     * and it cannot undo the reconcile.
     */
    private fun evictVideos(pks: List<String>) {
        if (pks.isEmpty()) return
        try {
            eviction.evict(pks)
        } catch (e: Exception) {
            // Intentionally ignored: a stale cached video only takes up space until the cache evicts it.
        }
    }

    private class PageOutcome(
        val cursor: SyncCursorEntity,
        val needThumbnails: List<RemoteMedia>,
        val removedPks: List<String>,
    )

    /** One page in one transaction: media, memberships, cursor and (at the end of a FULL walk) reconcile. */
    private suspend fun applyPage(
        progress: Progress,
        scope: String,
        cursor: SyncCursorEntity,
        page: Page<RemoteMedia>,
        knownCollections: Set<String>,
    ): PageOutcome {
        val runId = progress.run.id
        val mode = progress.run.mode
        val at = now()
        val pks = page.items.map { it.pk }
        val existing = mediaDao.byPks(pks).associateBy { it.pk }
        val members = collectionDao.memberships(scope, pks).associateBy { it.mediaPk }

        mediaDao.upsert(page.items.map { it.toEntity(existing[it.pk], at) })
        val memberships = page.items.mapIndexed { offset, item ->
            val known = members[item.pk]
            val key = if (mode == SyncMode.FULL || known == null) {
                SortKeys.key(cursor.walkBase, cursor.walkIndex + offset)
            } else {
                known.sortKey
            }
            CollectionMediaEntity(scope, item.pk, key, runId)
        }
        collectionDao.upsertMemberships(memberships)

        if (scope == ALL_SAVED_ID && client.reportsSavedCollectionIds) {
            page.items.zip(memberships).forEach { (item, member) ->
                // null means "the response doesn't say" (RemoteMedia): keep what we have, never treat it as "none".
                val ids = item.savedCollectionIds?.filter { it in knownCollections } ?: return@forEach
                // Only among the collections this run listed: a membership in one it did not list is left alone.
                collectionDao.deleteRealMembershipsExcept(item.pk, ids, knownCollections.toList())
                collectionDao.upsertMemberships(ids.map { CollectionMediaEntity(it, item.pk, member.sortKey, runId) })
            }
        }

        val reachedEnd = page.nextCursor == null
        if (mode == SyncMode.FULL && scope == ALL_SAVED_ID && reachedEnd &&
            cursor.walkIndex + page.items.size == 0L && collectionDao.maxSortKey(scope) != null
        ) {
            // A walk that ends having seen nothing, over a library that has items, is a broken feed, not an
            // account that unsaved everything: reconciling would wipe the library. Rolls this page back.
            throw InstagramException.ShapeChanged("empty saved feed")
        }
        val hitKnownItem = mode == SyncMode.QUICK && page.items.any { it.pk in members }
        val next = cursor.copy(
            nextCursor = page.nextCursor,
            walkIndex = cursor.walkIndex + page.items.size,
            done = reachedEnd || hitKnownItem,
        )
        syncDao.upsertCursor(next)

        val removed = if (mode == SyncMode.FULL && reachedEnd) reconcile(scope, runId, progress.run.startedAt) else emptyList()
        progress.update { r ->
            r.copy(newItems = r.newItems + page.items.count { it.pk !in existing }, seenItems = r.seenItems + page.items.size)
        }
        return PageOutcome(next, page.items.filter { existing[it.pk]?.thumbPath == null }, removed)
    }

    /**
     * Spec 7.2 step 5. Called only when a FULL walk of [scope] reached the end in this run. [runStartedAt] is the run row's
     * `startedAt`, which a resumed run keeps; both it and `firstSeenAt` come from the wall clock (`SyncController` and this
     * engine each default to `System::currentTimeMillis`).
     */
    private suspend fun reconcile(scope: String, runId: Long, runStartedAt: Long): List<String> {
        if (scope != ALL_SAVED_ID) {
            collectionDao.deleteUnseen(scope, runId)
            return emptyList()
        }
        val unsaved = collectionDao.unseenPks(ALL_SAVED_ID, runId)
        // P7 (amended by R71): a walk that reached the end but would remove at least RECONCILE_GUARD_MIN_ITEMS items AND more
        // than half of the library is a broken, partial or foreign feed far more often than an account that unsaved that much.
        // "The library" is what existed BEFORE this run: counting the members this run's own pages added would let a feed of
        // all-new items (another account's, say) enlarge the denominator until it passed. Items first seen by an earlier
        // attempt of this same run are new to the run too: they were first seen after its `startedAt`. Thrown inside the page's
        // transaction, so the page and its cursor roll back and the run stops. If the owner really did unsave (and save) that
        // much, Delete library then Full sync mirrors it.
        // R83: and it ends no later than the last DONE run. Items a refused, paused or failed run added after that and that a
        // Discard then left behind are not library either, or Discard and a new Full sync would count a foreign feed's own
        // items in. The +1: a run's last page and its finish can fall in the same millisecond.
        val lastDone = syncDao.lastDoneAt()
        val cutoff = if (lastDone == null) runStartedAt else minOf(runStartedAt, lastDone + 1)
        val before = collectionDao.memberCountSeenBefore(ALL_SAVED_ID, cutoff)
        if (unsaved.size >= RECONCILE_GUARD_MIN_ITEMS && unsaved.size * 2 > before) {
            throw InstagramException.ShapeChanged("full sync would remove ${unsaved.size} of $before items")
        }
        unsaved.chunked(500).forEach { chunk ->
            mediaDao.markRemoved(chunk, now())
            collectionDao.deleteAllMembershipsOf(chunk)
        }
        return unsaved
    }

    /** What became of one thumbnail. */
    private sealed interface Thumb {
        data class Stored(val path: String) : Thumb

        /** Unavailable (the fetcher said so) or the download, decoding or disk write failed. Counts as a failure. */
        data object Failed : Thumb

        /** Not tried: the CDN had already said 429 in this run. Not a failure; the item has no thumbnail yet. */
        data object Skipped : Thumb
    }

    /**
     * Fetches and stores [items]' thumbnails. [countFailures] is off for a resume's retry of items whose first
     * attempt may already have been counted, so one unavailable thumbnail is not counted twice.
     */
    private suspend fun cacheThumbnails(progress: Progress, items: List<ThumbnailTarget>, countFailures: Boolean = true) {
        if (items.isEmpty()) return
        val results = coroutineScope {
            items.map { item -> async { item.pk to cacheThumbnail(progress, item) } }.awaitAll()
        }
        results.forEach { (pk, thumb) -> if (thumb is Thumb.Stored) mediaDao.setThumbPath(pk, thumb.path) }
        progress.update { r ->
            r.copy(
                thumbsCached = r.thumbsCached + results.count { it.second is Thumb.Stored },
                failures = if (countFailures) r.failures + results.count { it.second == Thumb.Failed } else r.failures,
            )
        }
    }

    /**
     * One thumbnail. After the CDN's first 429 of this run ([Progress.cdnBlocked]) nothing more is requested: checked again
     * once the download holds a CDN permit, because it may have queued behind the one that got the 429. At most the
     * downloads already in flight (the policy's CDN concurrency, less the one that failed) still complete.
     */
    private suspend fun cacheThumbnail(progress: Progress, item: ThumbnailTarget): Thumb {
        if (progress.cdnBlocked) return Thumb.Skipped
        return try {
            var skipped = false
            val bytes = pacer.cdn {
                if (progress.cdnBlocked) {
                    skipped = true
                    null
                } else {
                    fetcher.fetch(item.url)
                }
            }
            if (skipped) {
                Thumb.Skipped
            } else {
                bytes?.let { Thumb.Stored(withContext(Dispatchers.IO) { thumbnails.write(item.pk, it) }) } ?: Thumb.Failed
            }
        } catch (e: CdnRateLimited) {
            progress.cdnBlocked = true
            Thumb.Failed // this download did fail; the ones skipped after it did not
        } catch (e: TimeoutCancellationException) {
            // A TimeoutCancellationException is a CancellationException, but one out of a download's own timeout is a failed
            // download, not a cancelled run. If it is this scope that is being cancelled, that still propagates.
            currentCoroutineContext().ensureActive()
            Thumb.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Thumb.Failed // network, decoding or disk trouble: this thumbnail counts as failed, the sync goes on
        }
    }

    private fun RemoteMedia.toEntity(previous: MediaEntity?, at: Long) = MediaEntity(
        pk = pk,
        code = code,
        type = type,
        author = author,
        caption = caption,
        takenAt = takenAt.toEpochMilli(),
        width = width,
        height = height,
        carouselCount = carouselCount,
        thumbPath = previous?.thumbPath,
        thumbUrl = thumbnailUrl,
        videoUrl = videoUrl,
        videoUrlExpiresAt = videoUrlExpiresAt?.toEpochMilli(),
        collectionNames = previous?.collectionNames.orEmpty(),
        firstSeenAt = previous?.firstSeenAt ?: at,
        lastSeenAt = at,
        removedAt = null,
    )

    /**
     * The run row plus this invocation's request budget and the session [epoch] it started under; every change is written
     * straight through.
     */
    private inner class Progress(var run: SyncRunEntity, val budget: Pacer.RunBudget, var epoch: Int) {
        private val requestsBefore = run.requestsUsed

        /**
         * Set by the first CDN 429 of this invocation; from then on no thumbnail is requested. Not persisted: a resume or the
         * next run tries again. Written by download coroutines that may run on different threads.
         */
        @Volatile
        var cdnBlocked = false

        suspend fun save() = syncDao.updateRun(run)

        suspend fun update(change: (SyncRunEntity) -> SyncRunEntity) {
            run = change(run).copy(requestsUsed = requestsBefore + budget.used)
            syncDao.updateRun(run)
        }

        suspend fun phase(text: String) = update { it.copy(phase = text) }

        suspend fun finish(status: SyncStatus, error: String?) {
            mediaDao.refreshCollectionNames()
            update { it.copy(status = status, lastError = error, finishedAt = now(), phase = "") }
        }
    }
}
