package io.github.yuriimurha.reels.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.media.CdnRateLimited
import io.github.yuriimurha.reels.data.media.MediaEviction
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsDocIdStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.instagram.fake.FakeFailures
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import io.github.yuriimurha.reels.instagram.fake.FakeLibrary
import io.github.yuriimurha.reels.instagram.web.GraphQlQuery
import io.github.yuriimurha.reels.instagram.web.InMemoryCookieStore
import io.github.yuriimurha.reels.instagram.web.InstagramTransport
import io.github.yuriimurha.reels.instagram.web.RawReply
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.instagram.web.WebInstagramClient
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.session.toStored
import io.github.yuriimurha.reels.sync.pacing.CooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.sync.pacing.RequestLog
import io.github.yuriimurha.reels.testutil.InMemoryLibraryAccount
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.loadAll
import io.github.yuriimurha.reels.transport.RepairPage
import io.github.yuriimurha.reels.transport.WatchedQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** Spelled out, not [SyncEngine.ANOTHER_ACCOUNT]: the banner's text is what the README tells the owner to look for. */
private const val ANOTHER_ACCOUNT = "This library belongs to another Instagram account. Delete library to switch."

private const val DAY = 86_400_000L

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val thumbs by lazy { ThumbnailStore(File(tmp.root, "thumbs")) }
    private val fetcher = MediaFetcher { url -> if (url.startsWith("fake://missing/")) null else byteArrayOf(1, 2, 3) }
    private val signals = RecordingSignals()

    /** The library's account, shared by every engine of a test as the app's settings are (R84). */
    private val account = InMemoryLibraryAccount()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    /** The app's settings, only for the tests that run the real names client and its repair over them. */
    private val settings by lazy { SettingsStore.open(scope = storeScope) { File(tmp.root, "settings.preferences_pb") } }

    @After
    fun close() {
        db.close()
        storeScope.cancel()
    }

    /** Every value the engine gave the names-stale notice, in order. */
    private val namesStale = mutableListOf<Boolean>()

    /** The engine's debug lines. */
    private val engineLog = mutableListOf<String>()

    /** The engine's clock, so a run's `startedAt` and the media's `firstSeenAt` come from the same source, as in the app. */
    private var clock: () -> Long = { 0L }

    private fun TestScope.engine(
        client: InstagramClient,
        log: RequestLog = InMemoryRequestLog(),
        cooldowns: CooldownStore = InMemoryCooldownStore(),
        mediaFetcher: MediaFetcher = fetcher,
        store: ThumbnailStore = thumbs,
        sessionSignals: SessionSignals = signals,
        eviction: MediaEviction = MediaEviction { },
        sessionUsable: suspend (Int) -> RunSession = { RunSession.USABLE },
        beforeRun: suspend () -> Unit = {},
        repairForced: suspend () -> Boolean = { false },
        clearRepairForced: suspend () -> Unit = {},
    ): SyncEngine {
        clock = { testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Fast, log, cooldowns, Random(1), now = { testScheduler.currentTime })
        return SyncEngine(
            client, pacer, db, mediaFetcher, store, sessionSignals, Random(1), now = { testScheduler.currentTime },
            eviction = eviction, sessionUsable = sessionUsable, libraryAccount = account, beforeRun = beforeRun,
            setNamesStale = { namesStale += it }, repairForced = repairForced, clearRepairForced = clearRepairForced,
            log = { engineLog += it },
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
        assertEquals(
            listOf("ok:test_account@5", "ok:test_account@5", "challenge:https://www.instagram.com/challenge/x/@5"),
            signals.events,
            "one sessionOk per run (the first QUICK run and this FULL one), then the stop",
        )
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

    /** Spec 8.3: an unsaved item's cached video goes with its thumbnail. Exactly the pks the reconcile removed, nothing else. */
    @Test
    fun reconcileEvictsCachedVideos() = runTest {
        val evicted = mutableListOf<String>()
        val client = smallClient()
        val engine = engine(client, eviction = { evicted += it })
        runSync(engine, SyncMode.QUICK)
        val all = client.library.allSaved()
        val gone = listOf(all[5].pk, all[9].pk)

        runSync(engine, SyncMode.FULL)
        assertEquals(emptyList(), evicted, "nothing was unsaved, so nothing is evicted")

        gone.forEach(client.library::unsave)
        runSync(engine, SyncMode.QUICK)
        assertEquals(emptyList(), evicted, "a QUICK run never deletes, so it never evicts")

        runSync(engine, SyncMode.FULL)
        assertEquals(gone.sorted(), evicted.sorted(), "exactly the removed pks")
    }

    /** A cache that cannot be written must not strand the run: the reconcile is already committed. */
    @Test
    fun aFailingEvictionDoesNotStopTheRun() = runTest {
        val client = smallClient()
        val engine = engine(client, eviction = { throw java.io.IOException("disk full") })
        runSync(engine, SyncMode.QUICK)
        val gone = client.library.allSaved()[5].pk
        client.library.unsave(gone)

        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertNotNull(db.mediaDao().byPks(listOf(gone)).single().removedAt)
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
        assertEquals(listOf("login@5"), signals.events, "the very first request failed: no session check succeeded, so no sessionOk")
        assertEquals(1, client.calls.size)
    }

    @Test
    fun successfulCurrentUserSignalsSessionOk() = runTest {
        val client = smallClient()
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(listOf("ok:test_account@5"), signals.events, "once per run, with the account's handle and the run's epoch")
    }

    @Test
    fun sessionOkCarriesTheEpochTheRunStartedWith() = runTest {
        val client = smallClient()
        // The owner logs out while the session check is in flight: its "ok" is for a session that is gone.
        client.failures = FakeFailures {
            if (it == 1) signals.current = 6
            null
        }
        runSync(engine(client), SyncMode.QUICK)
        assertEquals("ok:test_account@5", signals.events.first(), "read at the start of the run, so the session layer can tell it is stale")
    }

    @Test
    fun sessionOkIsSignalledBeforeTheRunsNextRequest() = runTest {
        val client = smallClient()
        val seen = mutableMapOf<Int, List<String>>()
        client.failures = FakeFailures { call ->
            seen[call] = signals.events.toList()
            null
        }
        runSync(engine(client), SyncMode.QUICK)
        assertEquals(emptyList(), seen[1], "nothing is signalled before currentUser answers")
        assertEquals(listOf("ok:test_account@5"), seen[2], "the next request (the collection list) starts after the signal")
    }

    @Test
    fun noSessionOkWhenTheSessionCheckFails() = runTest {
        for (failure in listOf(InstagramException.RateLimited(), InstagramException.Transient(), InstagramException.ShapeChanged("user"))) {
            signals.events.clear()
            val client = smallClient()
            client.failures = FakeFailures { failure } // every attempt fails, so a Transient is not rescued by its retry
            runSync(engine(client), SyncMode.QUICK)
            assertEquals(emptyList(), signals.events, "$failure")
        }
    }

    @Test
    fun stopSignalsCarryTheRunsEpoch() = runTest {
        val client = smallClient()
        // The owner logs out and logs in again while the run is in flight: the session layer's epoch moves on.
        client.failures = FakeFailures {
            if (it == 3) {
                signals.current = 6
                InstagramException.LoginRequired()
            } else {
                null
            }
        }
        val run = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals(listOf("ok:test_account@5", "login@5"), signals.events, "the run started under epoch 5, so every signal says 5")

        signals.events.clear()
        val failAt = client.calls.size + 3 // currentUser, collections, then the first page of the new run fails
        client.failures = FakeFailures {
            if (it == failAt) {
                signals.current = 8
                InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/")
            } else {
                null
            }
        }
        val next = runSync(engine(client), SyncMode.QUICK)
        assertEquals(SyncStatus.STOPPED_CHALLENGE, next.status)
        assertEquals(
            listOf("ok:test_account@6", "challenge:https://www.instagram.com/challenge/x/@6"),
            signals.events,
            "this run started under epoch 6 (what the first run's logout left behind)",
        )
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

    // ---- R82: a session that stops being usable stops the run before its next request ----

    /**
     * Final review I-1 (a), the reviewer's reproduction: the viewer's `mediaInfo` came back `challenge_required` while request
     * 3 of a Full sync was out, so the stored state is now Challenge. Before the fix the run sent 6 more requests and ended DONE.
     */
    @Test
    fun aChallengeStoredByAnotherLaneStopsTheRunBeforeItsNextRequest() = runTest {
        val client = FakeInstagramClient(FakeLibrary(seed = 3, itemCount = 60, collectionCount = 3), reportsSavedCollectionIds = false)
        var stored = RunSession.USABLE
        client.failures = FakeFailures { call ->
            if (call == 3) stored = RunSession.CHALLENGE
            null
        }

        val run = runSync(engine(client, sessionUsable = { stored }), SyncMode.FULL)

        assertEquals(3, client.calls.size, "nothing after the request during which the challenge was stored: ${client.calls}")
        assertEquals(SyncStatus.STOPPED_CHALLENGE, run.status)
        assertEquals("Instagram wants verification", run.lastError)
        assertEquals(
            listOf("ok:test_account@5"),
            signals.events,
            "no challenge signal: the session layer already holds the challenge, and a null URL would wipe the one it stored",
        )
    }

    @Test
    fun anExpiryStoredByAnotherLaneStopsTheRunAsSessionExpired() = runTest {
        val client = smallClient()
        var stored = RunSession.USABLE
        client.failures = FakeFailures { call ->
            if (call == 4) stored = RunSession.NOT_USABLE // Check now, or the lab, got login_required while page 2 was out
            null
        }

        val run = runSync(engine(client, sessionUsable = { stored }), SyncMode.QUICK)

        assertEquals(4, client.calls.size, "nothing after the expiry was stored: ${client.calls}")
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals("Session expired", run.lastError)
        assertEquals(listOf("ok:test_account@5"), signals.events, "no loginRequired: the session layer already knows")
    }

    /** I-1 (b): a paste (or a logout) mid-run starts a new epoch. The run must not carry on under a session it didn't start with. */
    @Test
    fun aPasteOrLogoutMidRunStopsItWithNoFurtherRequest() = runTest {
        val client = smallClient()
        // As the container wires it: usable only under the epoch the run started with (5).
        val sameEpoch: suspend (Int) -> RunSession = { epoch -> if (epoch == signals.current) RunSession.USABLE else RunSession.NOT_USABLE }
        client.failures = FakeFailures { call ->
            if (call == 3) signals.current = 6 // the owner pasted another session while page 1 was out
            null
        }

        val run = runSync(engine(client, sessionUsable = sameEpoch), SyncMode.QUICK)

        assertEquals(3, client.calls.size, "nothing under the new session: ${client.calls}")
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals("Session expired", run.lastError)
        assertEquals(listOf("ok:test_account@5"), signals.events)
    }

    /**
     * R107 (C4): the gate is asked BEFORE a request; a paste (or a logout, or a login as another account) can land while the
     * request is out. Its answer belongs to a session that is gone, so it is not written: the session is asked again when the
     * request returns, before any write, and the run stops as for an epoch change.
     */
    @Test
    fun aPageThatCameBackAfterTheSessionChangedIsNotWritten() = runTest {
        val client = smallClient()
        val sameEpoch: suspend (Int) -> RunSession = { epoch -> if (epoch == signals.current) RunSession.USABLE else RunSession.NOT_USABLE }
        client.failures = FakeFailures { call ->
            if (call == 3) signals.current = 6 // the owner pasted another session while page 1 was out
            null
        }

        val run = runSync(engine(client, sessionUsable = sameEpoch), SyncMode.QUICK)

        assertEquals(3, client.calls.size)
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals("Session expired", run.lastError)
        assertEquals(emptyList(), pks(ALL_SAVED_ID), "page 1 came back under the old session and was not written")
        assertEquals(0, run.newItems)
    }

    /** R107, and the R84 gap it closes: the account a session check names is not remembered when the session changed under it. */
    @Test
    fun aSessionCheckThatCameBackAfterTheSessionChangedRemembersNoAccount() = runTest {
        val client = smallClient()
        val sameEpoch: suspend (Int) -> RunSession = { epoch -> if (epoch == signals.current) RunSession.USABLE else RunSession.NOT_USABLE }
        client.failures = FakeFailures { call ->
            if (call == 1) signals.current = 6 // a login as another account while the run's currentUser was out
            null
        }

        val run = runSync(engine(client, sessionUsable = sameEpoch), SyncMode.QUICK)

        assertEquals(1, client.calls.size)
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertNull(account.pk(), "the library was not given the account of a session that is gone")
        assertEquals(emptyList(), signals.events, "and no session OK for it")
    }

    /**
     * The session is asked with the epoch the run captured at its start, before every request (inside the gate), the session
     * check included, and again when the request returns, before anything of its answer is written (R107).
     */
    @Test
    fun theGateIsAskedBeforeAndAfterEveryRequestWithTheRunsEpoch() = runTest {
        val client = smallClient()
        val asked = mutableListOf<Pair<Int, Int>>() // (requests made so far, epoch asked with)
        client.failures = FakeFailures { call ->
            if (call == 2) signals.current = 9 // the session layer moves on; the run keeps asking with ITS epoch
            null
        }

        runSync(engine(client, sessionUsable = { epoch -> asked += client.calls.size to epoch; RunSession.USABLE }), SyncMode.QUICK)

        assertEquals(
            client.calls.indices.flatMap { listOf(it to 5, it + 1 to 5) },
            asked,
            "two checks per request, one before it and one after it, always with epoch 5",
        )
    }

    /** I-1 (c): WorkManager re-runs work by itself after a process death. Under a challenged session it must send nothing at all. */
    @Test
    fun aRerunUnderAChallengedSessionSendsNothingNotEvenTheSessionCheck() = runTest {
        val client = smallClient()
        val engine = engine(client)
        client.failures = FakeFailures { if (it == 4) InstagramException.ChallengeRequired(null) else null }
        val id = newRun(SyncMode.FULL)
        engine.run(id)
        assertEquals(SyncStatus.STOPPED_CHALLENGE, db.syncDao().run(id)!!.status, "precondition: a run left resumable")
        val callsBefore = client.calls.size
        val requestsBefore = db.syncDao().run(id)!!.requestsUsed
        signals.events.clear()
        client.failures = FakeFailures { null }

        engine(client, sessionUsable = { RunSession.CHALLENGE }).run(id)

        val run = db.syncDao().run(id)!!
        assertEquals(callsBefore, client.calls.size, "not even currentUser: ${client.calls.drop(callsBefore)}")
        assertEquals(SyncStatus.STOPPED_CHALLENGE, run.status)
        assertEquals("Instagram wants verification", run.lastError)
        assertEquals(requestsBefore, run.requestsUsed, "no request was counted")
        assertEquals(emptyList(), signals.events)
    }

    @Test
    fun aRerunUnderAnExpiredSessionSendsNothing() = runTest {
        val client = smallClient()
        val run = runSync(engine(client, sessionUsable = { RunSession.NOT_USABLE }), SyncMode.QUICK)

        assertEquals(emptyList(), client.calls)
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals("Session expired", run.lastError)
        assertEquals(emptyList(), signals.events)
    }

    // ---- R91: every run is a new user action for the WebView transport ----

    @Test
    fun everyRunStartsByAllowingNewTransportAttemptsBeforeItsFirstRequest() = runTest {
        val client = smallClient()
        val requestsWhenTold = mutableListOf<Int>()
        val engine = engine(client, beforeRun = { requestsWhenTold += client.calls.size })

        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)
        assertTrue(client.calls.size > 1, "precondition: the run made several requests")
        assertEquals(listOf(0), requestsWhenTold, "told once per run, before anything was sent")

        // A Resume (or the next run) is a new action again: told once more, before that run's first request.
        val callsAfterTheFirstRun = client.calls.size
        runSync(engine, SyncMode.QUICK)
        assertEquals(listOf(0, callsAfterTheFirstRun), requestsWhenTold, "once for each run, not once per request")
    }

    @Test
    fun aRunThatStopsAtOnceWasToldToo() = runTest {
        val client = smallClient()
        var told = 0
        val run = runSync(engine(client, sessionUsable = { RunSession.NOT_USABLE }, beforeRun = { told++ }), SyncMode.QUICK)

        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals(1, told)
        assertEquals(emptyList(), client.calls)
    }

    /** The hook is the transport's, called on the main thread: whatever it throws, the run row must not stay RUNNING. */
    @Test
    fun aHookThatFailsPausesTheRunInsteadOfLeavingItRunning() = runTest {
        val client = smallClient()
        val run = runSync(engine(client, beforeRun = { throw IllegalStateException("no main looper") }), SyncMode.QUICK)

        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("Unexpected error: IllegalStateException", run.lastError)
        assertEquals(emptyList(), client.calls, "nothing is sent")
    }

    /** The fake backend has no session: the container builds its engine without a gate, and the default lets everything through. */
    @Test
    fun theFakeBackendsEngineIsUnaffected() = runTest {
        val client = smallClient()
        clock = { testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), InMemoryCooldownStore(), Random(1), now = { testScheduler.currentTime })
        // No sessionUsable argument, and SessionSignals.None, as AppContainer.syncEngine() builds it for Backend.Fake.
        val engine = SyncEngine(
            client, pacer, db, fetcher, thumbs, SessionSignals.None, Random(1), now = { testScheduler.currentTime },
            libraryAccount = account,
        )

        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
    }

    // ---- Final review I-2 (R83, R84): a foreign feed's items must not let a later Full sync remove the library ----

    private class ForeignFeed(val client: FakeInstagramClient, val originals: List<String>, val foreign: List<String>)

    /** A library of 40, synced; then the feed holds 100 items the library never had and none of the 40 (five pages). */
    private suspend fun TestScope.fortyThenAHundredForeign(log: RequestLog = InMemoryRequestLog()): ForeignFeed {
        val client = FakeInstagramClient(FakeLibrary(seed = 11, itemCount = 40, collectionCount = 2))
        assertEquals(SyncStatus.DONE, runSync(engine(client, log = log), SyncMode.QUICK).status)
        val originals = client.library.allSaved().map { it.pk }
        originals.forEach(client.library::unsave)
        val foreign = client.library.addNewSaves(100).map { it.pk }
        return ForeignFeed(client, originals, foreign)
    }

    /** The Sync screen's Discard paused run, through the real controller. */
    private suspend fun discard() {
        val noWorker = object : SyncScheduler {
            override fun enqueue(runId: Long) = Unit
            override fun cancel() = Unit
            override suspend fun isActive(): Boolean = false
        }
        SyncController(db, noWorker, now = clock).discardResumable()
    }

    /** The reviewer's first reproduction: a refused R1 left 80 foreign items; before R83, Discard then R2 removed all 40 originals. */
    @Test
    fun discardThenFullAfterARefusedForeignFeedRemovesNothing() = runTest {
        val feed = fortyThenAHundredForeign()
        val engine = engine(feed.client)
        val first = runSync(engine, SyncMode.FULL)
        assertEquals("Adapter needs repair: full sync would remove 40 of 40 items", first.lastError)
        assertEquals(80, db.mediaDao().byPks(feed.foreign).size, "precondition: the refused run's first four pages stayed")
        discard()

        val second = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.STOPPED_SHAPE, second.status, "R83: what a refused run added is not library yet: ${second.lastError}")
        assertEquals("Adapter needs repair: full sync would remove 40 of 40 items", second.lastError)
        assertEquals(emptyList(), removedAmong(feed.originals))
        assertTrue(pks(ALL_SAVED_ID).containsAll(feed.originals))
    }

    /** The same hole through a Full sync paused by the budget: Discard, then a new Full sync once the budget is back. */
    @Test
    fun aBudgetPausedForeignFullSyncThenDiscardThenFullRemovesNothing() = runTest {
        // 590 requests in the last 24 h: the QUICK run's 4 and the Full sync's first 6 fit; its page 5 (the last) is refused.
        val log = InMemoryRequestLog(List(590) { 0L })
        val feed = fortyThenAHundredForeign(log)
        val first = runSync(engine(feed.client, log = log), SyncMode.FULL)
        assertEquals(SyncStatus.PAUSED, first.status)
        assertEquals("24-hour budget reached", first.lastError)
        assertEquals(80, db.mediaDao().byPks(feed.foreign).size, "precondition: four pages of foreign items are in the library")
        discard()
        delay(Pacer.DAY_MS) // the 24 h budget frees up

        val second = runSync(engine(feed.client, log = log), SyncMode.FULL)

        assertEquals(SyncStatus.STOPPED_SHAPE, second.status, "R83: what a paused, discarded run added is not library yet: ${second.lastError}")
        assertEquals("Adapter needs repair: full sync would remove 40 of 40 items", second.lastError)
        assertEquals(emptyList(), removedAmong(feed.originals))
    }

    /** The feed and session of another account, as after a paste of that account's sessionid. */
    private class AnotherAccount(private val delegate: InstagramClient) : InstagramClient by delegate {
        override suspend fun currentUser(): Account {
            delegate.currentUser() // recorded in the fake's call log, so a test can count it
            return Account(pk = "2", username = "other_account")
        }
    }

    /**
     * The reviewer's QUICK path (Discard, then Sync, then Full sync), where the foreign feed is what it is in practice: another
     * account's (R84). Each run stops at its session check, so nothing of that feed is ever written and nothing is removed.
     */
    @Test
    fun anotherAccountsFeedNeverReachesTheLibraryThroughDiscardQuickAndFull() = runTest {
        val feed = fortyThenAHundredForeign()
        val engine = engine(AnotherAccount(feed.client))
        val callsBefore = feed.client.calls.size

        val first = runSync(engine, SyncMode.FULL)
        discard()
        val quick = runSync(engine, SyncMode.QUICK)
        discard()
        val full = runSync(engine, SyncMode.FULL)

        for (run in listOf(first, quick, full)) {
            assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
            assertEquals(ANOTHER_ACCOUNT, run.lastError)
        }
        assertEquals(List(3) { "currentUser" }, feed.client.calls.drop(callsBefore), "each run stopped right after its session check")
        assertEquals(emptyList(), db.mediaDao().byPks(feed.foreign), "nothing of the other account's feed was written")
        assertEquals(emptyList(), removedAmong(feed.originals))
        assertEquals(feed.originals.toSet(), pks(ALL_SAVED_ID).toSet())
        assertEquals("1", account.pk(), "the library still belongs to the first account")
    }

    @Test
    fun aRunUnderAnotherAccountStopsBeforeWritingAnything() = runTest {
        val client = smallClient()
        runSync(engine(client), SyncMode.QUICK)
        val itemsBefore = pks(ALL_SAVED_ID)
        val collectionsBefore = db.collectionDao().liveCollections().first()
        client.library.addNewSaves(5)
        val callsBefore = client.calls.size

        val run = runSync(engine(AnotherAccount(client)), SyncMode.FULL)

        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertEquals(ANOTHER_ACCOUNT, run.lastError)
        assertEquals(listOf("currentUser"), client.calls.drop(callsBefore), "not even the collection list")
        assertEquals(itemsBefore, pks(ALL_SAVED_ID))
        assertEquals(collectionsBefore, db.collectionDao().liveCollections().first())
        assertEquals(0, run.newItems)
        assertEquals("1", account.pk())
    }

    @Test
    fun theFirstSuccessfulSessionCheckRemembersTheAccountOnce() = runTest {
        val client = smallClient()
        client.failures = FakeFailures { if (it == 1) InstagramException.LoginRequired() else null }
        runSync(engine(client), SyncMode.QUICK)
        assertNull(account.pk(), "a failed session check remembers nothing")

        client.failures = FakeFailures { null }
        assertEquals(SyncStatus.DONE, runSync(engine(client), SyncMode.QUICK).status)
        assertEquals("1", account.pk(), "the fake account's pk")
        assertEquals(SyncStatus.DONE, runSync(engine(client), SyncMode.FULL).status, "the fake library keeps working")
        assertEquals(1, account.remembered, "written once, when none was stored")
    }

    /** After Delete library (which forgets the account) another account's library can be synced. */
    @Test
    fun aForgottenAccountLetsAnotherAccountSync() = runTest {
        val client = smallClient()
        runSync(engine(client), SyncMode.QUICK)
        db.deleteLibrary()
        account.forget()

        val run = runSync(engine(AnotherAccount(client)), SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals("2", account.pk())
        assertEquals(client.library.allSaved().map { it.pk }, pks(ALL_SAVED_ID))
    }

    /** M1: the session layer cannot even say which session this run starts under (no WebView provider, say). */
    @Test
    fun aSessionLayerThatCannotGiveAnEpochEndsTheRunPausedNotRunning() = runTest {
        val client = smallClient()
        val failing = object : SessionSignals {
            override fun epoch(): Int = error("no WebView provider")

            override suspend fun sessionOk(username: String, epoch: Int) = Unit

            override suspend fun loginRequired(epoch: Int) = Unit

            override suspend fun challengeRequired(challengeUrl: String?, epoch: Int) = Unit
        }

        val run = runSync(engine(client, sessionSignals = failing), SyncMode.QUICK)

        assertEquals(SyncStatus.PAUSED, run.status, "before: the exception escaped run() and the row stayed RUNNING")
        assertEquals("Unexpected error: IllegalStateException", run.lastError, "the message of the failure is never shown")
        assertEquals(emptyList(), client.calls)
    }

    // ---- Spec 2026-10-09: the names query, its one repair, the last names, placeholders and the notice ----

    /**
     * [fake]'s session and feed, with the names query scripted: one page of [names] (or what [pageFor] says), a stale reply for
     * the cursors in [staleAt], and [repair] for a repair. [beforeAnswer] runs while a names request is out. [events] lists every
     * names request and repair in order (the fake's own `calls` has the rest).
     */
    private class ScriptedNames(val fake: FakeInstagramClient) : InstagramClient by fake {
        val events = mutableListOf<String>()
        var names: List<RemoteCollection> = fake.library.collections
        var pageFor: (String?) -> Page<RemoteCollection> = { Page(names, null) }
        var staleAt: Set<String?> = emptySet()
        var repair: suspend () -> Page<RemoteCollection> = { throw InstagramException.RepairFailed("no query") }
        var beforeAnswer: suspend () -> Unit = {}

        override suspend fun collections(cursor: String?): Page<RemoteCollection> {
            events += "collections:$cursor"
            beforeAnswer()
            if (cursor in staleAt) throw InstagramException.StaleQuery(WebGraphQl.SAVED_COLLECTIONS.friendlyName)
            return pageFor(cursor)
        }

        override suspend fun repairCollections(): Page<RemoteCollection> {
            events += "repair"
            return repair()
        }
    }

    private suspend fun liveCollections(): List<CollectionEntity> = db.collectionDao().liveCollections().first()

    /** The live collections' names by id, All Saved aside. */
    private suspend fun names(): Map<String, String> = liveCollections().associate { it.id to it.name }

    private suspend fun assertMembershipsMatch(library: FakeLibrary, ids: Collection<String>) {
        for (id in ids) assertEquals(library.itemsIn(id).map { it.pk }, pks(id), "the members of $id")
    }

    @Test
    fun aStaleQueryRepairsOnceThenSyncs() = runTest {
        val client = ScriptedNames(smallClient())
        client.staleAt = setOf(null)
        client.repair = { Page(client.fake.library.collections, null) } // the site's own reply, through the page

        val run = runSync(engine(client), SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        assertNull(run.lastError)
        assertEquals(listOf("collections:null", "repair"), client.events, "one repair, and no second names request")
        assertEquals(mapOf("c1" to "Workouts", "c2" to "Recipes", "c3" to "Travel"), names())
        assertMembershipsMatch(client.fake.library, listOf("c1", "c2", "c3"))
        assertEquals(listOf(false), namesStale, "the names are fresh: no notice")
        assertEquals(listOf("collections query stale", "repair: learned new id"), engineLog)
        assertEquals(
            client.fake.calls.size + client.events.size,
            run.requestsUsed,
            "every call took one run-budget unit through the Pacer, the repair included",
        )
    }

    @Test
    fun aFailedRepairKeepsTheLastNamesAndRaisesTheNotice() = runTest {
        val client = ScriptedNames(smallClient())
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val before = liveCollections()
        assertEquals(mapOf("c1" to "Workouts", "c2" to "Recipes", "c3" to "Travel"), names(), "precondition")
        val library = client.fake.library
        val moved = library.allSaved().first { it.savedCollectionIds == listOf("c1") }.pk
        library.setCollections(moved, setOf("c2"))
        client.staleAt = setOf(null) // and the repair fails (the default)

        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status, "the sync goes on with the last names: ${run.lastError}")
        assertNull(run.lastError)
        assertEquals(before, liveCollections(), "every collection keeps its name, cover and place")
        assertEquals(0, removedCollectionCount(), "nothing is marked removed, not even in a FULL run")
        assertEquals(listOf(false, true), namesStale)
        assertMembershipsMatch(library, listOf("c1", "c2", "c3"))
        assertTrue(moved in pks("c2") && moved !in pks("c1"), "the items' own collection ids still apply")
        assertEquals(listOf("collections:null", "collections:null", "repair"), client.events)
    }

    @Test
    fun aSkippedRepairBehavesLikeAFailedOne() = runTest {
        val client = ScriptedNames(smallClient())
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val before = liveCollections()
        client.staleAt = setOf(null)
        client.repair = { throw InstagramException.RepairSkipped("limit") }

        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(before, liveCollections())
        assertEquals(0, removedCollectionCount())
        assertEquals(listOf(false, true), namesStale)
    }

    /**
     * Every other way a repair can give no names keeps the last ones too, and is never retried in the same run: a repaired reply
     * that is itself stale (the client learned nothing), one that failed for a moment, a repair refused for its missing handle.
     */
    @Test
    fun aRepairThatGivesNoNamesIsNeverRetriedInTheSameRun() = runTest {
        // What the engine logs of each (D-I2): a reply it could not use; a refusal or a failure the repairer logged itself.
        for ((failure, line) in listOf(
            InstagramException.StaleQuery(WebGraphQl.SAVED_COLLECTIONS.friendlyName) to "repair: failed (reply stale)",
            InstagramException.Transient() to "repair: failed (reply transient)",
            InstagramException.ShapeChanged("edges[0].node.collection_id") to "repair: failed (shape edges[0].node.collection_id)",
            InstagramException.RepairSkipped("no handle") to null,
            InstagramException.RepairFailed("page error") to null,
        )) {
            val client = ScriptedNames(smallClient())
            val engine = engine(client)
            runSync(engine, SyncMode.QUICK)
            val before = liveCollections()
            client.staleAt = setOf(null)
            client.repair = { throw failure }
            val feedCallsBefore = client.fake.calls.size
            engineLog.clear()

            val run = runSync(engine, SyncMode.QUICK)

            assertEquals(SyncStatus.DONE, run.status, "$failure")
            assertEquals(listOfNotNull("collections query stale", line), engineLog, "$failure")
            assertEquals(listOf("collections:null", "collections:null", "repair"), client.events, "$failure: one repair, no retry")
            assertEquals(before, liveCollections(), "$failure")
            assertEquals(true, namesStale.last(), "$failure")
            assertEquals(
                client.fake.calls.size - feedCallsBefore + 2,
                run.requestsUsed,
                "$failure: the run's feed requests, its one names request and its one repair",
            )
            db.deleteLibrary()
        }
    }

    @Test
    fun aRateLimitedRepairStopsTheRunLikeAnyRateLimit() = runTest {
        val cooldowns = InMemoryCooldownStore()
        val client = ScriptedNames(smallClient())
        val engine = engine(client, cooldowns = cooldowns)
        runSync(engine, SyncMode.QUICK)
        client.staleAt = setOf(null)
        client.repair = { throw InstagramException.RateLimited() }

        val run = runSync(engine, SyncMode.QUICK)

        assertEquals(SyncStatus.STOPPED_RATE_LIMIT, run.status)
        assertEquals("Instagram is limiting requests", run.lastError)
        assertNotNull(cooldowns.activeUntil(), "the Pacer armed the cooldown")
        assertEquals(listOf(false), namesStale, "a rate limit is no failed repair: the notice is left alone")
        val calls = client.fake.calls.size + client.events.size
        val next = runSync(engine, SyncMode.QUICK)
        assertEquals("Cooling down", next.lastError)
        assertEquals(calls, client.fake.calls.size + client.events.size, "nothing is sent during the cooldown")
    }

    /**
     * A login or challenge page stops the run as the same answer from an API call would. R14: a site reply of another shape is a
     * failed repair, not "Adapter needs repair": the sync goes on with the last names and the notice.
     */
    @Test
    fun aRepairThatLandsOnTheOwnersProblemStopsTheRunButAnUnreadableReplyFallsBack() = runTest {
        class Case(val failure: InstagramException, val status: SyncStatus, val error: String?, val signal: String?, val notice: List<Boolean>)
        for (case in listOf(
            Case(InstagramException.LoginRequired(), SyncStatus.STOPPED_LOGIN, "Session expired", "login@5", emptyList()),
            Case(InstagramException.ChallengeRequired(null), SyncStatus.STOPPED_CHALLENGE, "Instagram wants verification", "challenge:null@5", emptyList()),
            Case(InstagramException.ShapeChanged("data.viewer.collections_unified_with_auto_collections"), SyncStatus.DONE, null, null, listOf(true)),
        )) {
            signals.events.clear()
            namesStale.clear()
            val client = ScriptedNames(smallClient())
            client.staleAt = setOf(null)
            client.repair = { throw case.failure }

            val run = runSync(engine(client), SyncMode.QUICK)

            assertEquals(case.status, run.status, "${case.failure}")
            assertEquals(case.error, run.lastError)
            assertEquals(listOfNotNull("ok:test_account@5", case.signal), signals.events)
            if (case.status == SyncStatus.DONE) {
                assertEquals(listOf("currentUser", "saved:all:null"), client.fake.calls.take(2), "the walk runs: ${client.fake.calls}")
            } else {
                assertEquals(listOf("currentUser"), client.fake.calls, "no feed request after it")
            }
            assertEquals(case.notice, namesStale, "${case.failure}: a stop raises no notice, a fallback does")
            assertEquals(listOf("collections:null", "repair"), client.events, "one repair, never retried")
            db.deleteLibrary()
        }
    }

    /** The repair is a request like any other: inside the Pacer's gate, so never during a cooldown another lane armed. */
    @Test
    fun aRepairIsNeverAttemptedDuringACooldown() = runTest {
        val cooldowns = InMemoryCooldownStore()
        val client = ScriptedNames(smallClient())
        client.staleAt = setOf(null)
        client.repair = { Page(client.fake.library.collections, null) }
        client.beforeAnswer = { cooldowns.onRateLimited(testScheduler.currentTime) } // the viewer got a 429 meanwhile

        val run = runSync(engine(client, cooldowns = cooldowns), SyncMode.QUICK)

        assertEquals(SyncStatus.STOPPED_RATE_LIMIT, run.status)
        assertEquals("Cooling down", run.lastError)
        assertEquals(listOf("collections:null"), client.events, "no repair")
    }

    /** And it asks the session gate first: a logout that lands while the stale names request is out stops the run there. */
    @Test
    fun aRepairPassesTheSessionGateFirst() = runTest {
        var stored = RunSession.USABLE
        val client = ScriptedNames(smallClient())
        client.staleAt = setOf(null)
        client.repair = { Page(client.fake.library.collections, null) }
        client.beforeAnswer = { stored = RunSession.NOT_USABLE }

        val run = runSync(engine(client, sessionUsable = { stored }), SyncMode.QUICK)

        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals(listOf("collections:null"), client.events, "no repair under a session that is gone")
        assertEquals(emptyList(), namesStale)
    }

    @Test
    fun aGoodNamesFetchClearsTheNotice() = runTest {
        val client = ScriptedNames(smallClient())
        val engine = engine(client)
        client.staleAt = setOf(null)
        runSync(engine, SyncMode.QUICK)
        assertEquals(listOf(true), namesStale)

        client.staleAt = emptySet()
        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)

        assertEquals(listOf(true, false), namesStale)
    }

    /** A stale id on a later page is a broken answer, not one to repair: the last names stay, and no page is made. */
    @Test
    fun aStaleLaterPageFallsBackWithoutARepair() = runTest {
        val client = ScriptedNames(smallClient())
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val before = liveCollections()
        val library = client.fake.library
        client.pageFor = { cursor -> if (cursor == null) Page(library.collections.take(1), "p2") else Page(library.collections.drop(1), null) }
        client.staleAt = setOf("p2")
        client.repair = { Page(library.collections, null) }

        val run = runSync(engine, SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(listOf("collections:null", "collections:null", "collections:p2"), client.events)
        assertEquals(before, liveCollections(), "page 1 alone is not the whole list: nothing of it is written")
        assertEquals(0, removedCollectionCount())
        assertEquals(listOf(false, true), namesStale)
        assertEquals(emptyList(), engineLog, "only a stale first page is logged as the stale query")
    }

    /** R13: the client refuses a page that answers its own cursor; a cycle (A, B, A) is caught here, before it eats the budget. */
    @Test
    fun aCursorThatComesBackIsAShapeChange() = runTest {
        val client = ScriptedNames(smallClient())
        val next = mapOf(null to "a", "a" to "b", "b" to "a")
        client.pageFor = { cursor -> Page(client.fake.library.collections.take(1), next.getValue(cursor)) }

        val run = runSync(engine(client), SyncMode.QUICK)

        assertEquals(SyncStatus.STOPPED_SHAPE, run.status)
        assertEquals("Adapter needs repair: page_info.end_cursor", run.lastError)
        assertEquals(listOf("collections:null", "collections:a", "collections:b"), client.events)
        assertEquals(listOf("currentUser"), client.fake.calls)
        assertEquals(emptyMap(), names(), "nothing was written")
    }

    @Test
    fun anUnnamedCollectionSeenOnItemsGetsAPlaceholder() = runTest {
        val client = ScriptedNames(smallClient())
        val library = client.fake.library
        client.names = library.collections.take(1) // only c1 has a name yet
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        assertEquals(mapOf("c1" to "Workouts"), names(), "precondition: c2 and c3 were never named")

        client.staleAt = setOf(null)
        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.FULL).status)

        // Numbered from 1 in the order the feed first shows them, after the last collection.
        val sighted = library.allSaved().flatMap { it.savedCollectionIds.orEmpty() }.filter { it != "c1" }.distinct()
        assertEquals(setOf("c2", "c3"), sighted.toSet())
        assertEquals(mapOf("c1" to "Workouts", sighted[0] to "Collection 1", sighted[1] to "Collection 2"), names())
        assertEquals(listOf("c1") + sighted, liveCollections().map { it.id }, "placed after the last collection")
        assertMembershipsMatch(library, listOf("c1", "c2", "c3"))

        // The numbering goes on across runs.
        val newest = library.addNewSaves(1, setOf("c9")).single()
        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)
        assertEquals("Collection 3", names()["c9"])
        assertEquals(listOf(newest.pk), pks("c9"))

        // A later good names fetch renames them: same ids, the site's names.
        client.staleAt = emptySet()
        client.names = library.collections + RemoteCollection("c9", "Ninth", coverMediaPk = null)
        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)
        assertEquals(mapOf("c1" to "Workouts", "c2" to "Recipes", "c3" to "Travel", "c9" to "Ninth"), names())
        assertEquals(listOf(newest.pk), pks("c9"))
        assertEquals(listOf(false, true, true, false), namesStale)
    }

    /** The very first sync, under an id that is already stale and a repair that fails: All Saved is there, the rest are placeholders. */
    @Test
    fun aFirstSyncWithoutAnyNamesStillHasAllSavedAndEveryCollection() = runTest {
        val client = ScriptedNames(smallClient())
        client.staleAt = setOf(null)

        val run = runSync(engine(client), SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        val cards = db.collectionDao().cards().first()
        assertEquals(ALL_SAVED_ID, cards.first().id, "All Saved is listed")
        assertEquals(50, cards.first().count)
        val sighted = client.fake.library.allSaved().flatMap { it.savedCollectionIds.orEmpty() }.distinct()
        assertEquals(sighted.mapIndexed { i, id -> id to "Collection ${i + 1}" }.toMap(), names())
        assertMembershipsMatch(client.fake.library, sighted)
    }

    @Test
    fun anEmptyNameBecomesAPlaceholder() = runTest {
        val client = ScriptedNames(smallClient())
        val engine = engine(client)
        client.names = listOf(
            RemoteCollection("c1", "", coverMediaPk = null),
            RemoteCollection("c2", "Recipes", coverMediaPk = null),
            RemoteCollection("c3", " ", coverMediaPk = null),
        )
        runSync(engine, SyncMode.QUICK)
        assertEquals(mapOf("c1" to "Collection 1", "c2" to "Recipes", "c3" to "Collection 2"), names())

        runSync(engine, SyncMode.QUICK)
        assertEquals(mapOf("c1" to "Collection 1", "c2" to "Recipes", "c3" to "Collection 2"), names(), "the same names, not renumbered")

        // A name that goes empty keeps the one the app had; a real name replaces a placeholder.
        client.names = listOf(
            RemoteCollection("c1", "Workouts", coverMediaPk = null),
            RemoteCollection("c2", "", coverMediaPk = null),
            RemoteCollection("c3", "", coverMediaPk = null),
        )
        runSync(engine, SyncMode.QUICK)
        assertEquals(mapOf("c1" to "Workouts", "c2" to "Recipes", "c3" to "Collection 2"), names())

        // The next placeholder comes after the highest one left, so no two collections share one.
        client.staleAt = setOf(null)
        client.fake.library.addNewSaves(1, setOf("c9"))
        runSync(engine, SyncMode.QUICK)
        assertEquals("Collection 3", names()["c9"])
    }

    @Test
    fun aSyncSendsNoPerCollectionFeedRequests() = runTest {
        val client = smallClient() // the fake reports each item's collections, like the website (strategy A)

        val run = runSync(engine(client), SyncMode.FULL)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(1, client.calls.count { it.startsWith("collections:") }, "exactly one names request: ${client.calls}")
        assertEquals(listOf("currentUser", "collections:null", "saved:all:null", "saved:all:o:20", "saved:all:o:40"), client.calls)
        assertMembershipsMatch(client.library, listOf("c1", "c2", "c3"))
    }

    /** What the names query sees of the website: [current] is the doc id it runs now, any other is answered as stale. */
    private class NamesSite(var current: String, var name: String) : InstagramTransport {
        val sent = mutableListOf<String>()

        fun reply(): String =
            """{"data":{"viewer":{"collections_unified_with_auto_collections":{"edges":[""" +
                """{"node":{"collection_id":"ALL_MEDIA_AUTO_COLLECTION","collection_name":"All posts"}},""" +
                """{"node":{"collection_id":"17900000000000002","collection_name":"$name"}}],""" +
                """"page_info":{"has_next_page":false,"end_cursor":null}}}}}"""

        override suspend fun get(pathAndQuery: String): RawReply = error("only the names query is asked of this site")

        override suspend fun graphql(query: GraphQlQuery, docId: String, variables: String): RawReply {
            sent += docId
            val body = if (docId == current) reply() else """{"errors":[{"message":"unknown query"}],"data":null}"""
            return RawReply(200, "application/json", body)
        }
    }

    /**
     * Review Focus 5, the owner's check on the phone: Forget collections query id, then one sync. The real client, doc-id store
     * and repairer over the real settings, with a fake site and a fake page (R18/R21): the next sync sends no names query at all,
     * one repair runs although one ran an hour ago, the site's id is learned, and the next sync sends it with no repair. Every
     * id ever sent is one the site runs.
     */
    @Test
    fun forgetThenSyncRepairsExactlyOnce() = runTest {
        val site = NamesSite(current = "777", name = "Alpha")
        var pages = 0
        val repairer = QueryRepairer(
            settings,
            handle = { settings.session.first().handle },
            createPage = {
                pages++
                object : RepairPage {
                    override suspend fun watch(url: String, friendlyName: String, timeoutMs: Long) = WatchedQuery(site.current, 200, site.reply())

                    override fun destroy() = Unit
                }
            },
            now = { DAY + testScheduler.currentTime },
            main = StandardTestDispatcher(testScheduler),
            log = { engineLog += it },
        )
        val web = WebInstagramClient({ site }, InMemoryCookieStore(), SettingsDocIdStore(settings), repairer)
        val fake = smallClient()
        val client = object : InstagramClient by fake {
            override suspend fun collections(cursor: String?) = web.collections(cursor)

            override suspend fun repairCollections() = web.repairCollections()
        }
        val query = WebGraphQl.SAVED_COLLECTIONS.friendlyName
        settings.setSession(SessionState.Valid("tester").toStored())
        settings.setGraphqlDocId(query, "777")
        settings.setCollectionsRepairAt(DAY - 3_600_000L) // a repair ran an hour ago
        val engine = engine(client, repairForced = settings::collectionsForceRepair, clearRepairForced = settings::clearCollectionsForceRepair)
        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)
        assertEquals(listOf("777"), site.sent)
        assertEquals(mapOf("17900000000000002" to "Alpha"), names())

        settings.forgetCollectionsQueryId()
        site.name = "Beta"
        engineLog.clear()
        val run = runSync(engine, SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status, "${run.lastError}")
        assertEquals(listOf("777"), site.sent, "no names query: the repair's page sent the query itself")
        assertEquals(1, pages, "exactly one repair")
        assertEquals(listOf("collections query forced", "repair: start", "repair: learned new id"), engineLog)
        assertEquals("777", settings.graphqlDocId(query), "the site's id is learned")
        assertEquals(false, settings.collectionsForceRepair(), "a one-shot")
        assertEquals(mapOf("17900000000000002" to "Beta"), names(), "the names from the site's own reply")
        assertEquals(listOf(false, false), namesStale)

        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.QUICK).status)
        assertEquals(listOf("777", "777"), site.sent, "only ids the site runs, ever")
        assertEquals(1, pages, "no second repair")
    }

    /** R18: an armed Forget skips the names query and goes straight to the run's one repair, clearing the flag as it starts. */
    @Test
    fun anArmedForgetRepairsOnceWithoutAskingTheQuery() = runTest {
        val client = ScriptedNames(smallClient())
        var forced = true
        var forcedWhenTheRepairRan: Boolean? = null
        client.repair = {
            forcedWhenTheRepairRan = forced
            Page(client.fake.library.collections, null)
        }

        val run = runSync(engine(client, repairForced = { forced }, clearRepairForced = { forced = false }), SyncMode.QUICK)

        assertEquals(SyncStatus.DONE, run.status)
        assertEquals(listOf("repair"), client.events, "no names request, one repair")
        assertEquals(false, forcedWhenTheRepairRan, "cleared once the Pacer granted the attempt, before it ran")
        assertEquals(listOf("collections query forced", "repair: learned new id"), engineLog)
        assertEquals(mapOf("c1" to "Workouts", "c2" to "Recipes", "c3" to "Travel"), names())
        assertEquals(listOf(false), namesStale)
        assertEquals(client.fake.calls.size + 1, run.requestsUsed, "the repair is one run-budget unit, like the query it replaces")
    }

    /** R21: a Pacer refusal (here a cooldown another lane armed) sends nothing and leaves the flag set, for the next sync. */
    @Test
    fun theForcedRepairSurvivesACooldownRefusal() = runTest {
        val cooldowns = InMemoryCooldownStore()
        val client = ScriptedNames(smallClient())
        client.repair = { Page(client.fake.library.collections, null) }
        val coolingAfterTheCheck = object : InstagramClient by client {
            override suspend fun currentUser(): Account = client.currentUser().also { cooldowns.onRateLimited(testScheduler.currentTime) }
        }
        var forced = true

        val run = runSync(engine(coolingAfterTheCheck, cooldowns = cooldowns, repairForced = { forced }, clearRepairForced = { forced = false }), SyncMode.QUICK)

        assertEquals(SyncStatus.STOPPED_RATE_LIMIT, run.status)
        assertEquals("Cooling down", run.lastError)
        assertEquals(emptyList(), client.events, "no repair and no names request")
        assertTrue(forced, "the flag is kept for the next sync")
    }

    /** And a refusal by the session gate: a logout that lands just before the forced repair keeps it armed too. */
    @Test
    fun theForcedRepairSurvivesASessionRefusal() = runTest {
        var stored = RunSession.USABLE
        val client = ScriptedNames(smallClient())
        client.repair = { Page(client.fake.library.collections, null) }
        var forced = true
        val readFlag: suspend () -> Boolean = {
            stored = RunSession.NOT_USABLE // a logout lands right now
            forced
        }

        val run = runSync(engine(client, sessionUsable = { stored }, repairForced = readFlag, clearRepairForced = { forced = false }), SyncMode.QUICK)

        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals(emptyList(), client.events)
        assertTrue(forced)
    }

    /** The repairer's own refusals ("no handle", the limit) come after the Pacer granted the attempt: the flag is spent. */
    @Test
    fun theForcedRepairIsSpentByTheRepairersOwnRefusal() = runTest {
        for (refusal in listOf(InstagramException.RepairSkipped("no handle"), InstagramException.RepairSkipped("limit"))) {
            val client = ScriptedNames(smallClient())
            client.repair = { throw refusal }
            var forced = true
            namesStale.clear()

            val run = runSync(engine(client, repairForced = { forced }, clearRepairForced = { forced = false }), SyncMode.QUICK)

            assertEquals(SyncStatus.DONE, run.status, "the sync goes on with the last names")
            assertEquals(listOf("repair"), client.events)
            assertFalse(forced, "${refusal.message}: cleared")
            assertEquals(listOf(true), namesStale)
            db.deleteLibrary()
        }
    }

    /**
     * R20: a page without tokens sends no names query (`QueryNotSent`): it is never retried (no extra budget unit), the run keeps
     * the last names and walks the feed. On a later page too.
     */
    @Test
    fun aNamesQueryThatCouldNotBeSentFallsBackAtOnce() = runTest {
        val client = ScriptedNames(smallClient())
        val library = client.fake.library
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        val before = liveCollections()
        for (pageFor in listOf<(String?) -> Page<RemoteCollection>>(
            { throw InstagramException.QueryNotSent("no tokens") },
            { cursor -> if (cursor == null) Page(library.collections.take(1), "p2") else throw InstagramException.QueryNotSent("no tokens") },
        )) {
            client.pageFor = pageFor
            client.events.clear()
            namesStale.clear()
            val feedCallsBefore = client.fake.calls.size
            library.addNewSaves(1, setOf("c1"))

            val run = runSync(engine, SyncMode.QUICK)

            assertEquals(SyncStatus.DONE, run.status, "${run.lastError}")
            assertFalse(client.events.any { it == "repair" }, "no repair")
            assertEquals(client.events.distinct(), client.events, "each names page asked once: never retried")
            assertEquals(before, liveCollections(), "the last names")
            assertEquals(listOf(true), namesStale)
            val feed = client.fake.calls.drop(feedCallsBefore)
            assertTrue(feed.any { it.startsWith("saved:all") }, "the walk runs: $feed")
            assertEquals(feed.size + client.events.size, run.requestsUsed, "no budget unit beyond the requests made")
        }
    }

    /**
     * D-I2: one repair's debug lines, the repairer's and the engine's, in the order the app's one log gets them, over the real
     * client and repairer. "learned new id" comes only once the client has parsed the site's reply and kept its id; a reply it
     * can't use says why (R14: a reply of another shape is a failed repair too), and nothing is learned from it.
     */
    @Test
    fun aRepairSaysLearnedOnlyOnceTheIdIsLearned() = runTest {
        val query = WebGraphQl.SAVED_COLLECTIONS.friendlyName
        val site = NamesSite(current = "777", name = "Alpha")
        val stale = """{"errors":[{"message":"unknown query"}],"data":null}"""
        val noEdges = """{"data":{"viewer":{"collections_unified_with_auto_collections":{"page_info":{"has_next_page":false}}}}}"""
        class Case(val body: String?, val last: String, val learned: String?, val notice: Boolean)
        for (case in listOf(
            Case(site.reply(), "repair: learned new id", "777", notice = false),
            Case(stale, "repair: failed (reply stale)", null, notice = true),
            Case(noEdges, "repair: failed (shape edges)", null, notice = true),
            Case(null, "repair: failed (reply transient)", null, notice = true),
        )) {
            engineLog.clear()
            namesStale.clear()
            settings.setGraphqlDocId(query, null) // the built-in id, which this site no longer runs
            settings.setCollectionsRepairAt(null)
            val repairer = QueryRepairer(
                settings,
                handle = { "tester" },
                createPage = {
                    object : RepairPage {
                        override suspend fun watch(url: String, friendlyName: String, timeoutMs: Long) = WatchedQuery(site.current, 200, case.body)

                        override fun destroy() = Unit
                    }
                },
                now = { DAY + testScheduler.currentTime },
                main = StandardTestDispatcher(testScheduler),
                log = { engineLog += it },
            )
            val web = WebInstagramClient({ site }, InMemoryCookieStore(), SettingsDocIdStore(settings), repairer)
            val fake = smallClient()
            val client = object : InstagramClient by fake {
                override suspend fun collections(cursor: String?) = web.collections(cursor)

                override suspend fun repairCollections() = web.repairCollections()
            }

            val run = runSync(engine(client), SyncMode.QUICK)

            assertEquals(SyncStatus.DONE, run.status, "${case.body}: ${run.lastError}")
            assertEquals(listOf("collections query stale", "repair: start", case.last), engineLog)
            assertEquals(case.learned, settings.graphqlDocId(query))
            assertEquals(listOf(case.notice), namesStale)
            db.deleteLibrary()
        }
    }

    /**
     * A collection the last good listing no longer had (marked removed), seen again on items while the names can't be
     * refreshed, comes back under the name it had, never as a new "Collection N".
     */
    @Test
    fun aRemovedCollectionSeenAgainOnItemsComesBackUnderItsOwnName() = runTest {
        val client = ScriptedNames(smallClient())
        val library = client.fake.library
        val engine = engine(client)
        runSync(engine, SyncMode.QUICK)
        client.names = library.collections.filter { it.id != "c3" } // the site stopped listing c3
        runSync(engine, SyncMode.QUICK)
        assertEquals(mapOf("c1" to "Workouts", "c2" to "Recipes"), names(), "precondition: c3 is marked removed")
        assertEquals(1, removedCollectionCount())

        client.staleAt = setOf(null) // and the repair fails: the items' own collection ids bring c3 back
        assertEquals(SyncStatus.DONE, runSync(engine, SyncMode.FULL).status)

        assertEquals(mapOf("c1" to "Workouts", "c2" to "Recipes", "c3" to "Travel"), names(), "its stored name, not a placeholder")
        assertEquals(listOf("c1", "c2", "c3"), liveCollections().map { it.id }, "placed after the last collection")
        assertEquals(0, removedCollectionCount())
        assertMembershipsMatch(library, listOf("c1", "c2", "c3"))
    }

    /** Its stored name only while no live collection has it: a placeholder name a newer placeholder took is numbered afresh. */
    @Test
    fun aRevivedPlaceholderNeverSharesItsNameWithALiveOne() = runTest {
        val client = ScriptedNames(smallClient())
        db.collectionDao().upsert(
            listOf(
                CollectionEntity("c1", "Workouts", coverPk = null, position = 0),
                CollectionEntity("c2", "Collection 1", coverPk = null, position = 1),
                CollectionEntity("c3", "Collection 1", coverPk = null, position = 2, removedAt = 1L),
            ),
        )
        client.staleAt = setOf(null)

        assertEquals(SyncStatus.DONE, runSync(engine(client), SyncMode.QUICK).status)

        assertEquals(mapOf("c1" to "Workouts", "c2" to "Collection 1", "c3" to "Collection 2"), names())
    }

    private class ThrowingSignals : SessionSignals {
        override fun epoch(): Int = 1

        override suspend fun sessionOk(username: String, epoch: Int): Unit = error("signal sink is broken")

        override suspend fun loginRequired(epoch: Int): Unit = error("signal sink is broken")

        override suspend fun challengeRequired(challengeUrl: String?, epoch: Int): Unit = error("signal sink is broken")
    }

    /** Records each signal with the epoch it carried ("login@5"). [current] is what the session layer's epoch is right now. */
    private class RecordingSignals : SessionSignals {
        val events = mutableListOf<String>()

        var current = 5

        override fun epoch(): Int = current

        override suspend fun sessionOk(username: String, epoch: Int) {
            events += "ok:$username@$epoch"
        }

        override suspend fun loginRequired(epoch: Int) {
            events += "login@$epoch"
        }

        override suspend fun challengeRequired(challengeUrl: String?, epoch: Int) {
            events += "challenge:$challengeUrl@$epoch"
        }
    }
}
