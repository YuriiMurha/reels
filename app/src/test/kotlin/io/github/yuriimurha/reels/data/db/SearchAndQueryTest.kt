package io.github.yuriimurha.reels.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.instagram.MediaType
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

/** Runs the queries `DaoTest` does not reach, including the FTS4/unicode61 external-content join. */
@RunWith(AndroidJUnit4::class)
class SearchAndQueryTest {
    private val db = inMemoryDb()
    private val media = db.mediaDao()
    private val collections = db.collectionDao()
    private val sync = db.syncDao()

    @After
    fun close() = db.close()

    private val allTypes = MediaType.entries.map { it.name }

    private suspend fun givenSearchLibrary() {
        collections.upsert(
            listOf(
                CollectionEntity(ALL_SAVED_ID, "All Saved", coverPk = null, position = -1),
                CollectionEntity("c1", "Workouts", coverPk = null, position = 0),
            ),
        )
        media.upsert(
            listOf(
                mediaEntity("m1", caption = "Čučoriedkový koláč bez múky", author = "kuchyna_sk"),
                mediaEntity("m2", type = MediaType.IMAGE, caption = "Leg day routine", author = "gymrat"),
                mediaEntity("m3", caption = "Protein pancakes", author = "gymrat"),
                mediaEntity("m4", caption = "Leg press form", author = "gymrat", removedAt = 5),
            ),
        )
        collections.upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 10, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m2", sortKey = 20, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m3", sortKey = 30, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m4", sortKey = 40, lastSeenRunId = 1),
                CollectionMediaEntity("c1", "m2", sortKey = 20, lastSeenRunId = 1),
            ),
        )
        media.refreshCollectionNames()
    }

    private suspend fun search(match: String, types: List<String> = allTypes, scope: String = ALL_SAVED_ID) =
        media.pageSearch(match, types, scope).loadAll().map { it.pk }

    @Test
    fun searchMatchesCaptionAuthorAndCollectionNamesNewestFirst() = runTest {
        givenSearchLibrary()
        assertEquals(listOf("m2"), search("leg*"), "removed m4 is hidden")
        assertEquals(listOf("m3", "m2"), search("gymrat*"), "author is indexed, newest saved first")
        assertEquals(listOf("m2"), search("workouts*"), "collectionNames written by refreshCollectionNames is indexed")
    }

    @Test
    fun searchFoldsDiacriticsWithUnicode61() = runTest {
        givenSearchLibrary()
        assertEquals(listOf("m1"), search("cucoriedkovy*"))
        assertEquals(listOf("m1"), search("KOLAC*"))
    }

    @Test
    fun searchHonoursTypeAndScopeFilters() = runTest {
        givenSearchLibrary()
        assertEquals(listOf("m3"), search("gymrat*", types = listOf(MediaType.REEL.name)))
        assertEquals(listOf("m2"), search("gymrat*", scope = "c1"))
        assertEquals(emptyList(), search("pancakes*", scope = "c1"))
    }

    @Test
    fun searchIndexFollowsUpdates() = runTest {
        givenSearchLibrary()
        media.upsert(listOf(mediaEntity("m3", caption = "Protein waffles")))
        assertEquals(emptyList(), search("pancakes*"))
        assertEquals(listOf("m3"), search("waffles*"))
        collections.markRemovedExcept(keep = emptyList(), at = 9)
        media.refreshCollectionNames()
        assertEquals(emptyList(), search("workouts*"), "a removed collection's name leaves the index")
    }

    @Test
    fun markRemovedHidesItemsAndClearsTheThumbPath() = runTest {
        collections.upsert(listOf(CollectionEntity(ALL_SAVED_ID, "All Saved", coverPk = null, position = -1)))
        media.upsert(listOf(mediaEntity("m1"), mediaEntity("m2")))
        collections.upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 1, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m2", sortKey = 2, lastSeenRunId = 1),
            ),
        )
        media.setThumbPath("m1", "/thumbs/new.jpg")
        assertEquals("/thumbs/new.jpg", media.byPks(listOf("m1")).single().thumbPath)
        media.markRemoved(listOf("m2"), at = 7)
        val removed = media.byPks(listOf("m2")).single()
        assertEquals(7L, removed.removedAt)
        assertNull(removed.thumbPath)
        assertEquals(listOf("m1"), media.pageCollection(ALL_SAVED_ID).loadAll().map { it.pk })
    }

    @Test
    fun collectionBookkeepingQueries() = runTest {
        collections.upsert(
            listOf(
                CollectionEntity(ALL_SAVED_ID, "All Saved", coverPk = null, position = -1),
                CollectionEntity("c1", "Workouts", coverPk = null, position = 1),
                CollectionEntity("c2", "Recipes", coverPk = null, position = 0),
            ),
        )
        media.upsert(listOf(mediaEntity("m1"), mediaEntity("m2")))
        collections.upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 5, lastSeenRunId = 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m2", sortKey = 9, lastSeenRunId = 1),
                CollectionMediaEntity("c1", "m1", sortKey = 5, lastSeenRunId = 1),
                CollectionMediaEntity("c2", "m1", sortKey = 6, lastSeenRunId = 1),
            ),
        )
        assertEquals(listOf("c2", "c1"), collections.liveCollections().first().map { it.id })
        assertEquals(listOf("c2", "c1"), collections.collectionsOf("m1").map { it.id })
        assertEquals(9L, collections.maxSortKey(ALL_SAVED_ID))
        assertNull(collections.maxSortKey("none"))
        assertEquals(setOf("m1"), collections.memberships("c1", listOf("m1", "m2")).map { it.mediaPk }.toSet())

        collections.deleteRealMembershipsExcept("m1", keep = listOf("c1"), known = listOf("c1", "c2"))
        assertEquals(listOf("c1"), collections.collectionsOf("m1").map { it.id })
        assertEquals(
            listOf("m1"),
            collections.memberships(ALL_SAVED_ID, listOf("m1")).map { it.mediaPk },
            "the All Saved membership is never a 'real' one",
        )

        collections.markRemovedExcept(keep = listOf("c1"), at = 3)
        assertEquals(listOf("c1"), collections.liveCollections().first().map { it.id })
        assertEquals(listOf(ALL_SAVED_ID, "c1"), collections.cards().first().map { it.id })

        collections.deleteAllMembershipsOf(listOf("m1"))
        assertEquals(emptyList(), collections.memberships("c1", listOf("m1")))
        assertEquals(emptyList(), collections.memberships(ALL_SAVED_ID, listOf("m1")), "every scope of m1 goes")
        assertEquals(
            listOf("m2"),
            collections.memberships(ALL_SAVED_ID, listOf("m1", "m2")).map { it.mediaPk },
            "other media keep their memberships",
        )
        collections.deleteAllMemberships()
        assertNull(collections.maxSortKey(ALL_SAVED_ID))
    }

    @Test
    fun syncRunAndCursorQueries() = runTest {
        assertNull(sync.lastSyncAt().first())
        val quick = sync.insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = 0, finishedAt = 100))
        val full = sync.insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.DONE, startedAt = 0, finishedAt = 200))
        sync.insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.PAUSED, startedAt = 0, finishedAt = 300))
        assertEquals(200L, sync.lastSyncAt().first(), "only DONE runs count")
        assertEquals(200L, sync.lastFullSyncAt().first())
        assertEquals(SyncStatus.PAUSED, sync.latestRunFlow().first()!!.status)

        sync.updateRun(sync.run(quick)!!.copy(requestsUsed = 12, phase = "walk"))
        assertEquals(12, sync.run(quick)!!.requestsUsed)
        assertEquals("walk", sync.run(quick)!!.phase)

        assertNull(sync.cursor(full, "c1"))
        sync.upsertCursor(SyncCursorEntity(full, "c1", nextCursor = "abc", walkBase = 40, walkIndex = 2, done = false))
        sync.upsertCursor(SyncCursorEntity(full, "c1", nextCursor = null, walkBase = 40, walkIndex = 3, done = true))
        val cursor = sync.cursor(full, "c1")!!
        assertEquals(3L, cursor.walkIndex)
        assertEquals(true, cursor.done)
        assertNull(cursor.nextCursor)
    }

    @Test
    fun apiRequestLogQueries() = runTest {
        val log = db.apiRequestDao()
        listOf(1_000L, 2_000L, 3_000L).forEach { log.insert(ApiRequestEntity(at = it)) }
        assertEquals(2, log.countSince(1_000), "the window is exclusive of its lower edge")
        assertEquals(2_000L, log.oldestSince(1_000))
        assertNull(log.oldestSince(3_000))
        log.deleteUpTo(2_000)
        assertEquals(1, log.countSince(0))
    }

    @Test
    fun apiRequestLatestIsTheMaximumTimeOrNull() = runTest {
        val log = db.apiRequestDao()
        assertNull(log.latest(), "an empty log has no latest request")
        listOf(2_000L, 3_000L, 1_000L).forEach { log.insert(ApiRequestEntity(at = it)) }
        assertEquals(3_000L, log.latest(), "latest is the maximum, not the last inserted")
        log.deleteUpTo(2_000)
        assertEquals(3_000L, log.latest())
        log.deleteUpTo(3_000)
        assertNull(log.latest())
    }
}
