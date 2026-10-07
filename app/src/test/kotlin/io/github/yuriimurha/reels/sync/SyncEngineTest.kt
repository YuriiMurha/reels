package io.github.yuriimurha.reels.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.media.CdnRateLimited
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.instagram.fake.FakeFailures
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import io.github.yuriimurha.reels.instagram.fake.FakeLibrary
import io.github.yuriimurha.reels.sync.pacing.CooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.sync.pacing.RequestLog
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.loadAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val thumbs by lazy { ThumbnailStore(File(tmp.root, "thumbs")) }
    private val fetcher = MediaFetcher { url -> if (url.startsWith("fake://missing/")) null else byteArrayOf(1, 2, 3) }
    private val signals = RecordingSignals()

    @After
    fun close() = db.close()

    /** The engine's clock, so a run's `startedAt` and the media's `firstSeenAt` come from the same source, as in the app. */
    private var clock: () -> Long = { 0L }

    private fun TestScope.engine(
        client: InstagramClient,
        log: RequestLog = InMemoryRequestLog(),
        cooldowns: CooldownStore = InMemoryCooldownStore(),
        mediaFetcher: MediaFetcher = fetcher,
        store: ThumbnailStore = thumbs,
        sessionSignals: SessionSignals = signals,
    ): SyncEngine {
        clock = { testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Fast, log, cooldowns, Random(1), now = { testScheduler.currentTime })
        return SyncEngine(
            client, pacer, db, mediaFetcher, store, sessionSignals, Random(1), now = { testScheduler.currentTime },
        )
    }

    /** A run starts after everything the earlier runs stored, as on a phone where runs are seconds apart. */
    private suspend fun newRun(mode: SyncMode): Long {
        delay(1)
        return db.syncDao().insertRun(SyncRunEntity(mode = mode, status = SyncStatus.RUNNING, startedAt = clock()))
    }

    private suspend fun runSync(engine: SyncEngine, mode: SyncMode): SyncRunEntity {
        val id = newRun(mode)
        engine.run(id)
        return db.syncDao().run(id)!!
    }

    private suspend fun pks(collectionId: String) =
        db.mediaDao().pageCollection(collectionId).loadAll().map { it.pk }

    private fun smallClient() = FakeInstagramClient(FakeLibrary(itemCount = 50, collectionCount = 3))

    @Test
    fun firstSyncImportsEverythingNewestFirst() = runTest {
        val client = smallClient()
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
        for (collection in client.library.collections) {
            assertEquals(client.library.itemsIn(collection.id).map { it.pk }, pks(collection.id))
        }
        assertEquals(50, run.newItems)
        assertEquals(50, run.thumbsCached + run.failures)
    }

    @Test
    fun quickSyncStopsAtTheFirstKnownItem() = runTest {
        val client = smallClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val added = client.library.addNewSaves(3)
        val callsBefore = client.calls.size
        val run = runSync(engine, SyncMode.QUICK)
        assertEquals(listOf("saved:all:null"), client.calls.drop(callsBefore).filter { it.startsWith("saved:") })
        assertEquals(added.map { it.pk }, pks(ALL_SAVED_ID).take(3))
        assertEquals(3, run.newItems)
    }

    @Test
    fun newItemsAcrossSeveralPagesKeepFeedOrder() = runTest {
        val client = smallClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val added = client.library.addNewSaves(45)
        runSync(engine, SyncMode.QUICK)
        assertEquals(added.map { it.pk }, pks(ALL_SAVED_ID).take(45))
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
    }

    @Test
    fun interruptedFullSyncDeletesNothing() = runTest {
        val client = smallClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val gone = client.library.allSaved().last().pk
        client.library.unsave(gone)
        val failAt = client.calls.size + 4 // currentUser, collections, page 1, then page 2 fails
        client.failures = FakeFailures {
            if (it == failAt) InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/") else null
        }
        val run = runSync(engine, SyncMode.FULL)
        assertEquals(SyncStatus.STOPPED_CHALLENGE, run.status)
        assertEquals(failAt, client.calls.size, "no request after a challenge")
        assertTrue(gone in pks(ALL_SAVED_ID), "an interrupted walk must not delete")
        assertEquals(listOf("challenge:https://www.instagram.com/challenge/x/"), signals.events)
    }

    @Test
    fun completedFullSyncRemovesUnsavedAndAppliesMoves() = runTest {
        val client = smallClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val all = client.library.allSaved()
        val gone = all[5].pk
        val moved = all.first { it.pk != gone && "c1" in it.savedCollectionIds.orEmpty() }.pk
        val thumbFile = File(tmp.root, "thumbs/$gone.jpg")
        assertTrue(thumbFile.exists())

        client.library.unsave(gone)
        client.library.setCollections(moved, setOf("c2"))
        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertFalse(gone in pks(ALL_SAVED_ID))
        assertNotNull(db.mediaDao().byPks(listOf(gone)).single().removedAt)
        assertFalse(thumbFile.exists())
        assertTrue(moved in pks("c2"))
        assertFalse(moved in pks("c1"))
    }

    @Test
    fun resumedRunContinuesFromItsCursorWithTheSameNumbering() = runTest {
        val client = smallClient()
        val engine = engine(client)
        client.failures = FakeFailures { if (it == 4) InstagramException.ChallengeRequired(null) else null }
        val id = newRun(SyncMode.FULL)
        engine.run(id)
        assertEquals(SyncStatus.STOPPED_CHALLENGE, db.syncDao().run(id)!!.status)
        val cursorBefore = db.syncDao().cursor(id, ALL_SAVED_ID)!!
        assertEquals("o:20", cursorBefore.nextCursor)

        client.failures = FakeFailures { null }
        val callsBefore = client.calls.size
        engine.run(id)

        assertEquals(SyncStatus.DONE, db.syncDao().run(id)!!.status)
        assertEquals(
            listOf("saved:all:o:20", "saved:all:o:40"),
            client.calls.drop(callsBefore).filter { it.startsWith("saved:") },
        )
        assertEquals(cursorBefore.walkBase, db.syncDao().cursor(id, ALL_SAVED_ID)!!.walkBase)
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
    }

    @Test
    fun bothStrategiesProduceTheSameMemberships() = runTest {
        fun library() = FakeLibrary(seed = 3, itemCount = 60, collectionCount = 4)
        suspend fun memberships() = (1..4).associate { "c$it" to pks("c$it").toSet() }

        runSync(engine(FakeInstagramClient(library(), reportsSavedCollectionIds = true)), SyncMode.QUICK)
        val strategyA = memberships()
        db.deleteLibrary()
        runSync(engine(FakeInstagramClient(library(), reportsSavedCollectionIds = false)), SyncMode.QUICK)
        assertEquals(strategyA, memberships())
    }

    @Test
    fun loginRequiredStopsAndSignals() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it == 1) InstagramException.LoginRequired() else null }
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals(listOf("login"), signals.events)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun rateLimitStopsAndTheCooldownBlocksTheNextRun() = runTest {
        val client = smallClient()
        val engine = engine(client, cooldowns = InMemoryCooldownStore())
        client.failures = FakeFailures { if (it == 3) InstagramException.RateLimited() else null }
        assertEquals(SyncStatus.STOPPED_RATE_LIMIT, runSync(engine, SyncMode.QUICK).status)
        val calls = client.calls.size
        val next = runSync(engine, SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_RATE_LIMIT, next.status)
        assertEquals("Cooling down", next.lastError)
        assertEquals(calls, client.calls.size, "no request during a cooldown")
    }

    @Test
    fun shapeChangeStopsWithTheFieldPath() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it == 3) InstagramException.ShapeChanged("items[0].code") else null }
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertEquals("Adapter needs repair: items[0].code", run.lastError)
    }

    @Test
    fun transientFailuresAreRetriedThenTheRunPauses() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it >= 3) InstagramException.Transient() else null }
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals(7, client.calls.size, "currentUser, collections, then 5 attempts")
    }

    @Test
    fun dailyBudgetPausesTheRun() = runTest {
        val client = smallClient()
        val run = runSync(engine(client, log = InMemoryRequestLog(List(599) { 0L })), SyncMode.QUICK)
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("24-hour budget reached", run.lastError)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun unavailableThumbnailsAreCountedAndSyncContinues() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 200, collectionCount = 3))
        val run = runSync(engine(client), SyncMode.QUICK)
        val missing = client.library.allSaved().filter { it.thumbnailUrl.startsWith("fake://missing/") }
        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(missing.size, run.failures)
        assertTrue(missing.isNotEmpty())
        assertNull(db.mediaDao().byPks(listOf(missing.first().pk)).single().thumbPath)
    }

    @Test
    fun cancellationLeavesTheRunPaused() = runTest {
        val entered = CompletableDeferred<Unit>()
        val fake = smallClient()
        val blocking = object : InstagramClient by fake {
            override suspend fun currentUser(): Account {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val engine = engine(blocking)
        val id = newRun(SyncMode.QUICK)
        val job = launch { engine.run(id) }
        entered.await()
        job.cancelAndJoin()
        val run = db.syncDao().run(id)!!
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("Cancelled", run.lastError)
    }

    // ---- M1 device walkthrough ----

    @Test
    fun aResumedRunCachesTheThumbnailsAnInterruptedPageLeftBehind() = runTest {
        val client = smallClient()
        val fetched = AtomicInteger()
        var processDies = true
        val dyingFetcher = MediaFetcher { url ->
            // Page 1 (20 thumbnails) is cached, then the process dies while page 2's thumbnails are being fetched.
            if (processDies && fetched.incrementAndGet() > 20) throw CancellationException("process died")
            if (url.startsWith("fake://missing/")) null else byteArrayOf(1)
        }
        val engine = engine(client, mediaFetcher = dyingFetcher)
        val id = newRun(SyncMode.QUICK)
        try {
            engine.run(id)
        } catch (_: CancellationException) {
            // the worker is gone; its page 2 rows and cursor were already committed
        }
        val page2 = client.library.allSaved().subList(20, 40).map { it.pk }
        assertTrue(db.mediaDao().byPks(page2).all { it.thumbPath == null })

        processDies = false
        engine.run(id)

        val run = db.syncDao().run(id)!!
        assertEquals(SyncStatus.DONE, run.status)
        val unavailable = client.library.allSaved().filter { it.thumbnailUrl.startsWith("fake://missing/") }.map { it.pk }.toSet()
        val without = db.mediaDao().byPks(client.library.allSaved().map { it.pk }).filter { it.thumbPath == null }.map { it.pk }
        assertEquals(unavailable, without.toSet())
    }

    // ---- Fix round 1 (R17-R20) ----

    @Test
    fun anUnwritableThumbnailStoreCountsFailuresAndTheRunStillFinishes() = runTest {
        val client = smallClient()
        val blocked = File(tmp.root, "blocked").apply { writeText("a file where the directory should be") }
        val run = runSync(engine(client, store = ThumbnailStore(blocked)), SyncMode.QUICK)
        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(50, run.failures)
        assertEquals(0, run.thumbsCached)
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
        assertTrue(db.mediaDao().byPks(client.library.allSaved().map { it.pk }).all { it.thumbPath == null })
    }

    @Test
    fun aFetcherThatThrowsAnythingIsCountedAsAFailedThumbnail() = runTest {
        val client = smallClient()
        val badPk = client.library.allSaved()[7].pk
        val flaky = MediaFetcher { url -> if (url.endsWith("/$badPk")) error("decoder exploded") else byteArrayOf(1) }
        val run = runSync(engine(client, mediaFetcher = flaky), SyncMode.QUICK)
        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(1, run.failures)
        assertEquals(49, run.thumbsCached)
        assertNull(db.mediaDao().byPks(listOf(badPk)).single().thumbPath)
    }

    @Test
    fun aThrowingChallengeSignalStillLeavesTheRunStopped() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it == 3) InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/") else null }
        val run = runSync(engine(client, sessionSignals = ThrowingSignals()), SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_CHALLENGE, run.status)
        assertEquals("Instagram wants verification", run.lastError)
        assertNotNull(run.finishedAt)
    }

    @Test
    fun aThrowingLoginSignalStillLeavesTheRunStopped() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it == 1) InstagramException.LoginRequired() else null }
        val run = runSync(engine(client, sessionSignals = ThrowingSignals()), SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals("Session expired", run.lastError)
        assertNotNull(run.finishedAt)
    }

    @Test
    fun anUnexpectedErrorPausesTheRunWithoutPropagatingOrLeakingItsMessage() = runTest {
        val fake = smallClient()
        val broken = object : InstagramClient by fake {
            override suspend fun currentUser(): Account = error("cookie s1 is in this message")
        }
        val run = runSync(engine(broken), SyncMode.QUICK)
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("Unexpected error: IllegalStateException", run.lastError)
        assertNotNull(run.finishedAt)
    }

    @Test
    fun aFeedThatDoesNotSayWhichCollectionsLeavesMembershipsAlone() = runTest {
        val fake = smallClient()
        var hideFor: String? = null
        val client = object : InstagramClient by fake {
            override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> {
                val page = fake.savedMedia(collectionId, cursor)
                return page.copy(items = page.items.map { if (it.pk == hideFor) it.copy(savedCollectionIds = null) else it })
            }
        }
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val target = fake.library.allSaved().take(20).first { it.savedCollectionIds.orEmpty().isNotEmpty() }
        val collectionIds = target.savedCollectionIds.orEmpty()
        hideFor = target.pk

        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)
        collectionIds.forEach { assertTrue(target.pk in pks(it), "QUICK kept ${target.pk} in $it") }

        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.FULL).status)
        collectionIds.forEach { assertTrue(target.pk in pks(it), "FULL kept ${target.pk} in $it") }
        assertTrue(target.pk in pks(ALL_SAVED_ID))
    }

    private suspend fun TestScope.quickRunThatReachesTheEndDeletesNothing(reportsSavedCollectionIds: Boolean) {
        val client = FakeInstagramClient(
            FakeLibrary(seed = 5, itemCount = 10, collectionCount = 2),
            reportsSavedCollectionIds = reportsSavedCollectionIds,
        )
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val all = client.library.allSaved()
        val gone = all[5].pk
        val moved = all.first { it.pk != gone && it.savedCollectionIds == listOf("c1") }.pk
        client.library.unsave(gone)
        client.library.setCollections(moved, setOf("c2"))
        val callsBefore = client.calls.size

        val run = runSync(engine, SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        val saved = client.calls.drop(callsBefore).filter { it.startsWith("saved:") }
        assertTrue(saved.all { it.endsWith(":null") }, "every scope was a single page that ended: $saved")
        assertTrue(gone in pks(ALL_SAVED_ID), "a QUICK run never deletes, even at the end of the feed")
        assertNull(db.mediaDao().byPks(listOf(gone)).single().removedAt)
        assertTrue(File(tmp.root, "thumbs/$gone.jpg").exists())
        if (!reportsSavedCollectionIds) {
            assertTrue(moved in pks("c1"), "strategy B: a QUICK run does not reconcile c1")
            assertTrue(moved in pks("c2"), "strategy B: the QUICK walk of c2 adds it")
        }
    }

    @Test
    fun quickRunReachingTheEndOfTheFeedDeletesNothingStrategyA() = runTest {
        quickRunThatReachesTheEndDeletesNothing(reportsSavedCollectionIds = true)
    }

    @Test
    fun quickRunReachingTheEndOfTheFeedDeletesNothingStrategyB() = runTest {
        quickRunThatReachesTheEndDeletesNothing(reportsSavedCollectionIds = false)
    }

    private fun strategyBClient() = FakeInstagramClient(
        FakeLibrary(seed = 7, itemCount = 120, collectionCount = 2),
        reportsSavedCollectionIds = false,
    )

    @Test
    fun interruptedFullStrategyBRunKeepsEveryCollectionMembership() = runTest {
        val client = strategyBClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val c1 = client.library.itemsIn("c1")
        assertTrue(c1.size > 40, "c1 needs several pages, has ${c1.size}")
        val moved = c1.drop(25).first { it.savedCollectionIds == listOf("c1") }.pk
        client.library.setCollections(moved, setOf("c2"))
        val c1Before = pks("c1").toSet()
        val c2Before = pks("c2").toSet()

        client.failures = FakeFailures {
            if (client.calls.last() == "saved:c1:o:20") InstagramException.ChallengeRequired(null) else null
        }
        val id = newRun(SyncMode.FULL)
        engine.run(id)

        assertEquals(SyncStatus.STOPPED_CHALLENGE, db.syncDao().run(id)!!.status)
        assertEquals("saved:c1:o:20", client.calls.last(), "the walk of c1 stopped on its second page")
        assertEquals(c1Before, pks("c1").toSet(), "an interrupted walk must not delete from c1")
        assertTrue(moved in pks("c1"))
        assertEquals(c2Before, pks("c2").toSet())

        client.failures = FakeFailures { null }
        engine.run(id)
        assertEquals(SyncStatus.DONE, db.syncDao().run(id)!!.status)
        assertFalse(moved in pks("c1"), "the resumed walk reached the end, so it reconciles")
        assertTrue(moved in pks("c2"))
        assertEquals(client.library.itemsIn("c1").map { it.pk }, pks("c1"))
    }

    @Test
    fun completedFullStrategyBRunMovesItemsBetweenCollections() = runTest {
        val client = strategyBClient()
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val moved = client.library.itemsIn("c1").first { it.savedCollectionIds == listOf("c1") }.pk
        client.library.setCollections(moved, setOf("c2"))

        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertFalse(moved in pks("c1"))
        assertTrue(moved in pks("c2"))
        for (collection in client.library.collections) {
            assertEquals(client.library.itemsIn(collection.id).map { it.pk }, pks(collection.id))
        }
    }

    private class EmptyFeed(private val delegate: InstagramClient) : InstagramClient by delegate {
        override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> =
            if (collectionId == null) Page(emptyList(), null) else delegate.savedMedia(collectionId, cursor)
    }

    @Test
    fun anEmptySavedFeedInAFullRunStopsInsteadOfDeletingEverything() = runTest {
        val fake = smallClient()
        runSync(engine(fake), SyncMode.QUICK)
        val everything = fake.library.allSaved().map { it.pk }

        val run = runSync(engine(EmptyFeed(fake)), SyncMode.FULL)

        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertEquals("Adapter needs repair: empty saved feed", run.lastError)
        assertEquals(everything, pks(ALL_SAVED_ID))
        assertTrue(db.mediaDao().byPks(everything).all { it.removedAt == null })
        for (collection in fake.library.collections) {
            assertEquals(fake.library.itemsIn(collection.id).map { it.pk }, pks(collection.id))
        }
        assertEquals(everything.size, File(tmp.root, "thumbs").listFiles().orEmpty().size)
    }

    @Test
    fun anEmptySavedFeedInAQuickRunChangesNothing() = runTest {
        val fake = smallClient()
        runSync(engine(fake), SyncMode.QUICK)
        val everything = fake.library.allSaved().map { it.pk }

        val run = runSync(engine(EmptyFeed(fake)), SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(everything, pks(ALL_SAVED_ID))
    }

    @Test
    fun aCollectionThatBecameEmptyIsReconciledNotTreatedAsABrokenFeed() = runTest {
        val client = FakeInstagramClient(
            FakeLibrary(seed = 2, itemCount = 40, collectionCount = 2),
            reportsSavedCollectionIds = false,
        )
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        client.library.itemsIn("c2").forEach { client.library.setCollections(it.pk, setOf("c1")) }
        assertTrue(pks("c2").isNotEmpty())

        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(emptyList(), pks("c2"))
        assertEquals(client.library.itemsIn("c1").map { it.pk }, pks("c1"))
    }

    @Test
    fun aFullRunOnAnAccountThatHasNothingSavedIsFine() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 0, collectionCount = 2))
        val run = runSync(engine(client), SyncMode.FULL)
        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(emptyList(), pks(ALL_SAVED_ID))
    }

    // ---- M4 safety gates (Task 8) ----

    /** A response that lost its `items`: every page of collections is empty. */
    private class NoCollections(private val delegate: InstagramClient) : InstagramClient by delegate {
        override suspend fun collections(cursor: String?): Page<RemoteCollection> = Page(emptyList(), null)
    }

    /** The collection list of a run in which [hiddenId] is missing, while saved items still say they belong to it. */
    private class WithoutCollection(private val delegate: InstagramClient, private val hiddenId: String) : InstagramClient by delegate {
        override suspend fun collections(cursor: String?): Page<RemoteCollection> {
            val page = delegate.collections(cursor)
            return page.copy(items = page.items.filter { it.id != hiddenId })
        }
    }

    private fun removedCollectionCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM collection WHERE removedAt IS NOT NULL")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Test
    fun emptyCollectionListWithLiveCollectionsStopsWithoutMarkingRemoved() = runTest {
        val fake = smallClient()
        runSync(engine(fake), SyncMode.QUICK)
        val before = db.collectionDao().liveCollections().first().map { it.id }
        assertEquals(listOf("c1", "c2", "c3"), before.sorted(), "precondition: the first run listed three collections")
        assertEquals(0, removedCollectionCount())
        val callsBefore = fake.calls.size

        val run = runSync(engine(NoCollections(fake)), SyncMode.QUICK)

        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertEquals("Adapter needs repair: empty collection list", run.lastError)
        assertEquals(before, db.collectionDao().liveCollections().first().map { it.id })
        assertEquals(0, removedCollectionCount(), "no collection, All Saved included, was marked removed")
        assertEquals(listOf("currentUser"), fake.calls.drop(callsBefore), "the run stopped before walking anything")
    }

    @Test
    fun anAccountWithoutCollectionsIsNotABrokenListing() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 30, collectionCount = 0))
        val engine = engine(client)

        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)
        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.FULL).status, "still no collections, still not an error")
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
    }

    @Test
    fun strategyAOnlyRewritesKnownCollections() = runTest {
        val fake = smallClient()
        val target = fake.library.allSaved().first().pk
        fake.library.setCollections(target, setOf("c1", "c3"))
        runSync(engine(fake), SyncMode.QUICK)
        assertTrue(target in pks("c1") && target in pks("c3"), "precondition")
        // The item moves from c1 to c2 and stays in c3, but this run's list does not mention c3.
        fake.library.setCollections(target, setOf("c2", "c3"))

        val run = runSync(engine(WithoutCollection(fake, "c3")), SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        assertFalse(target in pks("c1"), "c1 is listed, so the rewrite removes the item from it")
        assertTrue(target in pks("c2"), "and adds it to c2")
        assertTrue(target in pks("c3"), "c3 was not in this run's list: its memberships are not this run's to rewrite")
    }

    private class UnsavedLibrary(
        val everyone: List<String>,
        val gone: List<String>,
        val fresh: List<String>,
        /** The originals that had a thumbnail file before the FULL sync. */
        val thumbnailed: List<String>,
        val run: SyncRunEntity,
    )

    private fun thumbnailFile(pk: String) = File(tmp.root, "thumbs/$pk.jpg")

    /**
     * A library of [total] items, synced; then [unsaved] of them are unsaved on Instagram, [fresh] items nobody has seen are
     * saved on top, and a FULL sync runs.
     */
    private suspend fun TestScope.fullSyncAfterUnsaving(total: Int, unsaved: Int, fresh: Int = 0): UnsavedLibrary {
        val client = FakeInstagramClient(FakeLibrary(seed = 11, itemCount = total, collectionCount = 2))
        val engine = engine(client)
        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)
        val everyone = client.library.allSaved().map { it.pk }
        assertEquals(total, everyone.size)
        assertEquals(total, pks(ALL_SAVED_ID).size)
        val gone = everyone.shuffled(Random(5)).take(unsaved)
        gone.forEach(client.library::unsave)
        val added = client.library.addNewSaves(fresh).map { it.pk }
        val thumbnailed = everyone.filter { thumbnailFile(it).exists() }
        return UnsavedLibrary(everyone, gone, added, thumbnailed, runSync(engine, SyncMode.FULL))
    }

    private suspend fun removedAmong(pks: List<String>) = db.mediaDao().byPks(pks).filter { it.removedAt != null }.map { it.pk }

    @Test
    fun reconcileGuardRefusesMassRemoval() = runTest {
        val library = fullSyncAfterUnsaving(total = 100, unsaved = 70)
        val run = library.run

        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertTrue("would remove 70 of 100" in run.lastError.orEmpty(), "lastError: ${run.lastError}")
        assertEquals("Adapter needs repair: full sync would remove 70 of 100 items", run.lastError)
        assertEquals(emptyList(), removedAmong(library.everyone), "every item has removedAt == null")
        assertEquals(library.everyone.toSet(), pks(ALL_SAVED_ID).toSet(), "and every item is still in All Saved")

        // The page that reached the end rolls back with its cursor, so the run can only ever resume from the page before.
        val cursor = checkNotNull(db.syncDao().cursor(run.id, ALL_SAVED_ID))
        assertFalse(cursor.done)
        assertEquals("o:20", cursor.nextCursor)
        val lastPage = library.everyone.filter { it !in library.gone }.drop(20)
        assertEquals(10, lastPage.size)
        assertTrue(
            db.collectionDao().memberships(ALL_SAVED_ID, lastPage).all { it.lastSeenRunId != run.id },
            "the rolled-back page left no membership stamped with this run",
        )
    }

    @Test
    fun reconcileGuardLetsASmallRemovalThrough() = runTest {
        val library = fullSyncAfterUnsaving(total = 100, unsaved = 19)

        assertEquals(SyncStatus.DONE, library.run.status)
        assertEquals(library.gone.toSet(), removedAmong(library.everyone).toSet())
        assertEquals(81, pks(ALL_SAVED_ID).size)
    }

    @Test
    fun reconcileGuardRefusesRemovingSixtyOfAHundred() = runTest {
        val library = fullSyncAfterUnsaving(total = 100, unsaved = 60)

        assertEquals(SyncStatus.STOPPED_SHAPE, library.run.status)
        assertEquals("Adapter needs repair: full sync would remove 60 of 100 items", library.run.lastError)
        assertEquals(emptyList(), removedAmong(library.everyone))
    }

    /** The denominator is the library as it was BEFORE the run: items the feed adds do not enlarge it (R71). */
    private class GuardCase(val total: Int, val unsaved: Int, val fresh: Int, val refused: Boolean)

    /** At least 20 AND more than half: 19 of 25 (too few), 20 of 40 (exactly half), 50 of 100 (exactly half) pass. */
    @Test
    fun reconcileGuardBoundaries() = runTest {
        for (case in listOf(
            GuardCase(25, 19, 0, refused = false),
            GuardCase(30, 20, 0, refused = true),
            GuardCase(40, 20, 0, refused = false),
            GuardCase(40, 21, 0, refused = true),
            GuardCase(100, 50, 0, refused = false),
            GuardCase(100, 51, 0, refused = true),
            GuardCase(100, 50, 60, refused = false), // new saves don't turn exactly half into less than half
            GuardCase(100, 51, 60, refused = true),
            GuardCase(100, 100, 100, refused = true),
        )) {
            db.deleteLibrary()
            val library = fullSyncAfterUnsaving(case.total, case.unsaved, case.fresh)
            val label = "removing ${case.unsaved} of ${case.total} while ${case.fresh} items are new"
            val expected = if (case.refused) SyncStatus.STOPPED_SHAPE else SyncStatus.DONE
            assertEquals(expected, library.run.status, label)
            assertEquals(
                if (case.refused) emptyList() else library.gone.sorted(),
                removedAmong(library.everyone).sorted(),
                label,
            )
        }
    }

    /** A feed that is not this library (every pk new) must not be able to talk the guard into a mass removal with its own items. */
    @Test
    fun aFeedOfOnlyNewItemsIsNotThisLibraryAndRemovesNothing() = runTest {
        val library = fullSyncAfterUnsaving(total = 100, unsaved = 100, fresh = 100)
        val run = library.run

        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertEquals("Adapter needs repair: full sync would remove 100 of 100 items", run.lastError)
        assertEquals(emptyList(), removedAmong(library.everyone), "no original was marked removed")
        assertTrue(pks(ALL_SAVED_ID).containsAll(library.everyone), "every original is still in All Saved")
        assertTrue(library.thumbnailed.size >= 99, "precondition: the originals had thumbnails")
        assertEquals(emptyList(), library.thumbnailed.filter { !thumbnailFile(it).exists() }, "and their thumbnails were not deleted")
    }

    @Test
    fun fortyOldPlusFiftyNewItemsRefusesTheRemovalOfSixty() = runTest {
        val library = fullSyncAfterUnsaving(total = 100, unsaved = 60, fresh = 50)

        assertEquals(SyncStatus.STOPPED_SHAPE, library.run.status)
        assertEquals("Adapter needs repair: full sync would remove 60 of 100 items", library.run.lastError)
        assertEquals(emptyList(), removedAmong(library.everyone))
        assertEquals(emptyList(), library.thumbnailed.filter { !thumbnailFile(it).exists() })
    }

    @Test
    fun ninetyOldPlusFiftyNewItemsRemovesTheTenThatAreGone() = runTest {
        val library = fullSyncAfterUnsaving(total = 100, unsaved = 10, fresh = 50)

        assertEquals(SyncStatus.DONE, library.run.status)
        assertEquals(library.gone.toSet(), removedAmong(library.everyone).toSet())
        assertEquals(140, pks(ALL_SAVED_ID).size, "90 old and 50 new")
        assertEquals(library.fresh.toSet(), pks(ALL_SAVED_ID).filter { it in library.fresh }.toSet())
    }

    /**
     * R71: the reference is the run row's `startedAt`, which a resumed run keeps. Items first seen by an earlier attempt of
     * the SAME run are new to the run, so they must not count as "the library before the run" in the attempt that finishes it.
     */
    @Test
    fun aRunResumedAcrossTwoAttemptsStillCountsOnlyTheItemsThatExistedBeforeTheRun() = runTest {
        val client = FakeInstagramClient(FakeLibrary(seed = 11, itemCount = 100, collectionCount = 2))
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val originals = client.library.allSaved().map { it.pk }
        originals.take(60).forEach(client.library::unsave)
        val fresh = client.library.addNewSaves(60).map { it.pk } // the feed is now 60 new items, then 40 of the old ones
        val failAt = client.calls.size + 5 // currentUser, collections, page 1, page 2, then page 3 is stopped
        client.failures = FakeFailures { if (it == failAt) InstagramException.ChallengeRequired(null) else null }
        val id = newRun(SyncMode.FULL)

        engine.run(id)

        assertEquals(SyncStatus.STOPPED_CHALLENGE, db.syncDao().run(id)!!.status)
        assertEquals(40, db.mediaDao().byPks(fresh).size, "the first attempt stored two pages of new items")

        client.failures = FakeFailures { null }
        engine.run(id)

        val run = db.syncDao().run(id)!!
        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertEquals("Adapter needs repair: full sync would remove 60 of 100 items", run.lastError)
        assertEquals(emptyList(), removedAmong(originals))
    }

    @Test
    fun cdnRateLimitStopsThumbnailFetchesForTheRun() = runTest {
        val client = smallClient() // 50 items, so three pages
        val fetches = AtomicInteger()
        val fetchesWhenAPageWasRequested = mutableListOf<Int>()
        val spy = object : InstagramClient by client {
            override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> {
                fetchesWhenAPageWasRequested += fetches.get()
                return client.savedMedia(collectionId, cursor)
            }
        }
        val limited = MediaFetcher {
            val number = fetches.incrementAndGet()
            delay(10) // the answer takes a while, so the downloads started before the 429 arrives are in flight when it does
            if (number == 3) throw CdnRateLimited()
            byteArrayOf(1)
        }

        val run = runSync(engine(spy, mediaFetcher = limited), SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        assertTrue(fetches.get() >= 3, "the third fetch is the one that got the 429")
        assertTrue(fetchesWhenAPageWasRequested.first() == 0, "nothing was fetched before the first page came in")
        assertTrue(
            fetches.get() <= PacingPolicy.Fast.cdnConcurrency,
            "after the 429 only the downloads already in flight may still happen, not ${fetches.get()} fetches",
        )
        assertEquals(3, fetchesWhenAPageWasRequested.size)
        assertEquals(
            listOf(fetches.get(), fetches.get()),
            fetchesWhenAPageWasRequested.drop(1),
            "every later page made no fetch at all",
        )
        assertEquals(1, run.failures, "only the download that really failed counts, not the ones that were never tried")
        assertEquals(fetches.get() - 1, run.thumbsCached)
        val withoutThumbnail = db.mediaDao().byPks(client.library.allSaved().map { it.pk }).count { it.thumbPath == null }
        assertEquals(50 - run.thumbsCached, withoutThumbnail, "the rest simply have no thumbnail yet")
        assertEquals(50, run.newItems, "the items themselves were all imported")
    }

    @Test
    fun theNextFullSyncFetchesTheThumbnailsACdnRateLimitSkipped() = runTest {
        val client = smallClient()
        val limited = MediaFetcher { throw CdnRateLimited() }
        val first = runSync(engine(client, mediaFetcher = limited), SyncMode.QUICK)
        assertEquals(SyncStatus.DONE, first.status)
        assertEquals(0, first.thumbsCached)

        val second = runSync(engine(client), SyncMode.FULL)

        assertEquals(SyncStatus.DONE, second.status)
        assertEquals(0, second.failures)
        assertTrue(db.mediaDao().byPks(client.library.allSaved().map { it.pk }).all { it.thumbPath != null })
    }

    @Test
    fun downloadTimeoutDoesNotCancelTheRun() = runTest {
        val client = smallClient()
        val slowPk = client.library.allSaved()[7].pk
        val timingOut = MediaFetcher { url ->
            if (url.endsWith("/$slowPk")) withTimeout(1) { delay(10) }
            byteArrayOf(1)
        }

        val run = runSync(engine(client, mediaFetcher = timingOut), SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(1, run.failures)
        assertEquals(49, run.thumbsCached)
        assertNull(db.mediaDao().byPks(listOf(slowPk)).single().thumbPath)
    }

    private class ThrowingSignals : SessionSignals {
        override suspend fun loginRequired(): Unit = error("signal sink is broken")

        override suspend fun challengeRequired(challengeUrl: String?): Unit = error("signal sink is broken")
    }

    private class RecordingSignals : SessionSignals {
        val events = mutableListOf<String>()

        override suspend fun loginRequired() {
            events += "login"
        }

        override suspend fun challengeRequired(challengeUrl: String?) {
            events += "challenge:$challengeUrl"
        }
    }
}
