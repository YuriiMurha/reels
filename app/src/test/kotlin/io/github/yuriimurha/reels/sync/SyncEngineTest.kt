package io.github.yuriimurha.reels.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
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

    private fun TestScope.engine(
        client: InstagramClient,
        log: RequestLog = InMemoryRequestLog(),
        cooldowns: CooldownStore = InMemoryCooldownStore(),
        mediaFetcher: MediaFetcher = fetcher,
        store: ThumbnailStore = thumbs,
        sessionSignals: SessionSignals = signals,
    ): SyncEngine {
        val pacer = Pacer(PacingPolicy.Fast, log, cooldowns, Random(1), now = { testScheduler.currentTime })
        return SyncEngine(
            client, pacer, db, mediaFetcher, store, sessionSignals, Random(1), now = { testScheduler.currentTime },
        )
    }

    private suspend fun newRun(mode: SyncMode): Long =
        db.syncDao().insertRun(SyncRunEntity(mode = mode, status = SyncStatus.RUNNING, startedAt = 0))

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
