package io.github.yuriimurha.reels.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.loadAll
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private val db = inMemoryDb()
    private val media = db.mediaDao()
    private val collections = db.collectionDao()
    private val sync = db.syncDao()

    @After
    fun close() = db.close()

    private suspend fun givenLibrary() {
        collections.upsert(
            listOf(
                CollectionEntity(ALL_SAVED_ID, "All Saved", coverPk = null, position = -1),
                CollectionEntity("c1", "Workouts", coverPk = null, position = 0),
                CollectionEntity("c2", "Recipes", coverPk = "m1", position = 1),
            ),
        )
        media.upsert(listOf(mediaEntity("m1"), mediaEntity("m2"), mediaEntity("m3"), mediaEntity("m4", removedAt = 5)))
        collections.upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 10, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m2", sortKey = 30, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m3", sortKey = 20, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m4", sortKey = 40, lastSeenRunId = 1),
                CollectionMediaEntity("c1", "m2", sortKey = 30, lastSeenRunId = 1),
                CollectionMediaEntity("c2", "m1", sortKey = 10, lastSeenRunId = 1),
            ),
        )
    }

    @Test
    fun collectionPagesAreNewestSavedFirstAndHideRemoved() = runTest {
        givenLibrary()
        assertEquals(listOf("m2", "m3", "m1"), media.pageCollection(ALL_SAVED_ID).loadAll().map { it.pk })
    }

    @Test
    fun uncategorizedIgnoresMembershipInRemovedCollections() = runTest {
        givenLibrary()
        assertEquals(listOf("m3"), media.pageUncategorized().loadAll().map { it.pk })
        collections.markRemovedExcept(keep = listOf("c1"), at = 99)
        assertEquals(listOf("m3", "m1"), media.pageUncategorized().loadAll().map { it.pk })
        assertEquals(2, media.uncategorizedCount().first())
    }

    @Test
    fun cardsPutAllSavedFirstAndFallBackToNewestCover() = runTest {
        givenLibrary()
        val cards = collections.cards().first()
        assertEquals(listOf(ALL_SAVED_ID, "c1", "c2"), cards.map { it.id })
        assertEquals(listOf(3, 1, 1), cards.map { it.count })
        assertEquals("/thumbs/m2.jpg", cards[0].coverThumbPath, "All Saved falls back to its newest live item")
        assertEquals("/thumbs/m1.jpg", cards[2].coverThumbPath, "explicit cover wins")
    }

    @Test
    fun refreshCollectionNamesJoinsLiveRealCollections() = runTest {
        givenLibrary()
        collections.upsertMemberships(listOf(CollectionMediaEntity("c2", "m2", sortKey = 30, lastSeenRunId = 1)))
        media.refreshCollectionNames()
        assertEquals("Recipes Workouts", media.byPks(listOf("m2")).single().collectionNames)
        assertEquals("", media.byPks(listOf("m3")).single().collectionNames)
    }

    @Test
    fun unseenMembershipsAreFoundAndDeletedPerScope() = runTest {
        givenLibrary()
        collections.upsertMemberships(listOf(CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 10, lastSeenRunId = 2)))
        assertEquals(setOf("m2", "m3", "m4"), collections.unseenPks(ALL_SAVED_ID, runId = 2).toSet())
        collections.deleteUnseen("c1", runId = 2)
        assertEquals(emptyList(), media.pageCollection("c1").loadAll())
    }

    @Test
    fun deleteLibraryKeepsTheRequestLog() = runTest {
        givenLibrary()
        db.apiRequestDao().insert(ApiRequestEntity(at = 1_000))
        sync.insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = 0))
        db.deleteLibrary()
        assertEquals(emptyList(), collections.cards().first())
        assertNull(sync.latestRun())
        assertEquals(1, db.apiRequestDao().countSince(0), "the rolling 24 h budget must survive a library wipe")
    }

    @Test
    fun pauseRunningRunsOnlyTouchesRunning() = runTest {
        val running = sync.insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.RUNNING, startedAt = 0))
        val done = sync.insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = 0))
        sync.pauseRunningRuns("Interrupted")
        assertEquals(SyncStatus.PAUSED, sync.run(running)!!.status)
        assertEquals("Interrupted", sync.run(running)!!.lastError)
        assertEquals(SyncStatus.DONE, sync.run(done)!!.status)
    }

    @Test
    fun resumableStatuses() {
        val resumable = SyncStatus.entries.filter { it.isResumable }.toSet()
        assertEquals(
            setOf(
                SyncStatus.PAUSED, SyncStatus.STOPPED_CHALLENGE, SyncStatus.STOPPED_LOGIN,
                SyncStatus.STOPPED_RATE_LIMIT, SyncStatus.STOPPED_SHAPE,
            ),
            resumable,
        )
    }
}
