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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
) {
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
        )
        progress.save()
        try {
            progress.phase("Checking session")
            call(progress) { client.currentUser() }
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
        } catch (e: InstagramException.ChallengeRequired) {
            progress.finish(SyncStatus.STOPPED_CHALLENGE, "Instagram wants verification")
            notifySession { signals.challengeRequired(e.challengeUrl) }
        } catch (e: InstagramException.LoginRequired) {
            progress.finish(SyncStatus.STOPPED_LOGIN, "Session expired")
            notifySession { signals.loginRequired() }
        } catch (e: InstagramException.RateLimited) {
            progress.finish(SyncStatus.STOPPED_RATE_LIMIT, "Instagram is limiting requests")
        } catch (e: PacerRefusal.CoolingDown) {
            progress.finish(SyncStatus.STOPPED_RATE_LIMIT, "Cooling down")
        } catch (e: InstagramException.ShapeChanged) {
            progress.finish(SyncStatus.STOPPED_SHAPE, "Adapter needs repair: ${e.fieldPath}")
        } catch (e: InstagramException.Transient) {
            progress.finish(SyncStatus.PAUSED, "Network problem, try again later")
        } catch (e: PacerRefusal.RunBudgetReached) {
            progress.finish(SyncStatus.PAUSED, "Run budget reached, tap Sync to continue")
        } catch (e: PacerRefusal.DailyBudgetReached) {
            progress.finish(SyncStatus.PAUSED, "24-hour budget reached")
        } catch (e: Exception) {
            // Last resort: never strand the run as RUNNING. The message is dropped on purpose (it may hold session data).
            progress.finish(SyncStatus.PAUSED, "Unexpected error: ${e::class.simpleName ?: "Exception"}")
        }
    }

    /**
     * Tells the session layer about a stop whose status is already written. A failing receiver must not
     * change that status or escape; cancellation still propagates.
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

    private suspend fun <T> call(progress: Progress, request: suspend () -> T): T =
        retryTransient(random) { pacer.sync(progress.budget, request) }

    private suspend fun fetchCollections(progress: Progress): List<CollectionEntity> {
        val remote = mutableListOf<RemoteCollection>()
        var cursor: String? = null
        do {
            val from = cursor
            val page = call(progress) { client.collections(from) }
            remote += page.items
            cursor = page.nextCursor
        } while (cursor != null)
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
            cacheThumbnails(progress, outcome.needThumbnails.map { ThumbnailTarget(it.pk, it.thumbnailUrl) })
        }
        progress.update { it.copy(collectionsDone = it.collectionsDone + 1) }
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
                collectionDao.deleteRealMembershipsExcept(item.pk, ids)
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

        val removed = if (mode == SyncMode.FULL && reachedEnd) reconcile(scope, runId) else emptyList()
        progress.update { r ->
            r.copy(newItems = r.newItems + page.items.count { it.pk !in existing }, seenItems = r.seenItems + page.items.size)
        }
        return PageOutcome(next, page.items.filter { existing[it.pk]?.thumbPath == null }, removed)
    }

    /** Spec 7.2 step 5. Called only when a FULL walk of [scope] reached the end in this run. */
    private suspend fun reconcile(scope: String, runId: Long): List<String> {
        if (scope != ALL_SAVED_ID) {
            collectionDao.deleteUnseen(scope, runId)
            return emptyList()
        }
        val unsaved = collectionDao.unseenPks(ALL_SAVED_ID, runId)
        unsaved.chunked(500).forEach { chunk ->
            mediaDao.markRemoved(chunk, now())
            collectionDao.deleteAllMembershipsOf(chunk)
        }
        return unsaved
    }

    /**
     * Fetches and stores [items]' thumbnails. [countFailures] is off for a resume's retry of items whose first
     * attempt may already have been counted, so one unavailable thumbnail is not counted twice.
     */
    private suspend fun cacheThumbnails(progress: Progress, items: List<ThumbnailTarget>, countFailures: Boolean = true) {
        if (items.isEmpty()) return
        val results = coroutineScope {
            items.map { item ->
                async {
                    val path = try {
                        val bytes = pacer.cdn { fetcher.fetch(item.url) }
                        bytes?.let { withContext(Dispatchers.IO) { thumbnails.write(item.pk, it) } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // network, decoding or disk trouble: this thumbnail counts as failed, the sync goes on
                    }
                    item.pk to path
                }
            }.awaitAll()
        }
        results.forEach { (pk, path) -> if (path != null) mediaDao.setThumbPath(pk, path) }
        progress.update { r ->
            r.copy(
                thumbsCached = r.thumbsCached + results.count { it.second != null },
                failures = if (countFailures) r.failures + results.count { it.second == null } else r.failures,
            )
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

    /** The run row plus this invocation's request budget; every change is written straight through. */
    private inner class Progress(var run: SyncRunEntity, val budget: Pacer.RunBudget) {
        private val requestsBefore = run.requestsUsed

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
