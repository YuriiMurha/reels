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
    fun setVideoLinkRewritesOnlyThatRowsLink() = runTest {
        media.upsert(listOf(mediaEntity("m1", videoUrl = "https://v.test/old", videoUrlExpiresAt = 5), mediaEntity("m2", videoUrl = "https://v.test/two", videoUrlExpiresAt = 6)))

        media.setVideoLink("m1", "https://v.test/new", 99)

        val (one, two) = media.byPks(listOf("m1", "m2")).sortedBy { it.pk }
        assertEquals("https://v.test/new" to 99L, one.videoUrl to one.videoUrlExpiresAt)
        assertEquals("https://v.test/two" to 6L, two.videoUrl to two.videoUrlExpiresAt)
        assertEquals("caption m1", one.caption, "nothing else on the row changed")
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
        collections.upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 10, lastSeenRunId = 2),
                // c1 also holds a row that run 2 did see; only its stale row (m2) may go.
                CollectionMediaEntity("c1", "m1", sortKey = 10, lastSeenRunId = 2),
            ),
        )
        assertEquals(setOf("m2", "m3", "m4"), collections.unseenPks(ALL_SAVED_ID, runId = 2).toSet())
        collections.deleteUnseen("c1", runId = 2)
        assertEquals(listOf("m1"), media.pageCollection("c1").loadAll().map { it.pk }, "only c1's stale row is deleted")
        // The delete is scoped to c1: stale rows in other collections, All Saved included, survive.
        val allPks = listOf("m1", "m2", "m3", "m4")
        assertEquals(allPks.toSet(), collections.memberships(ALL_SAVED_ID, allPks).map { it.mediaPk }.toSet())
        assertEquals(listOf("m2", "m3", "m1"), media.pageCollection(ALL_SAVED_ID).loadAll().map { it.pk })
        assertEquals(listOf("m1"), media.pageCollection("c2").loadAll().map { it.pk })
    }

    @Test
    fun liveCollectionCountSkipsAllSavedAndRemovedCollections() = runTest {
        givenLibrary() // All Saved, c1, c2
        assertEquals(2, collections.liveCollectionCount(), "All Saved is not a real collection")
        collections.markRemovedExcept(keep = listOf("c1"), at = 9)
        assertEquals(1, collections.liveCollectionCount())
        collections.markRemovedExcept(keep = emptyList(), at = 10)
        assertEquals(0, collections.liveCollectionCount())
    }

    /** R71: the share a FULL reconcile removes is measured against the members that existed before the run began. */
    @Test
    fun memberCountSeenBeforeCountsOnlyMembersWhoseMediaWasFirstSeenEarlier() = runTest {
        collections.upsert(
            listOf(
                CollectionEntity(ALL_SAVED_ID, "All Saved", coverPk = null, position = -1),
                CollectionEntity("c1", "Workouts", coverPk = null, position = 0),
            ),
        )
        media.upsert(
            listOf(
                mediaEntity("m1").copy(firstSeenAt = 5),
                mediaEntity("m2").copy(firstSeenAt = 10),
                mediaEntity("m3").copy(firstSeenAt = 20),
                mediaEntity("m4").copy(firstSeenAt = 1), // first seen early, but a member of c1 only
            ),
        )
        collections.upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", sortKey = 1, lastSeenRunId = 2),
                CollectionMediaEntity(ALL_SAVED_ID, "m2", sortKey = 2, lastSeenRunId = 2),
                CollectionMediaEntity(ALL_SAVED_ID, "m3", sortKey = 3, lastSeenRunId = 2),
                CollectionMediaEntity("c1", "m4", sortKey = 4, lastSeenRunId = 2),
            ),
        )
        assertEquals(0, collections.memberCountSeenBefore(ALL_SAVED_ID, before = 5), "strictly before: m1 was first seen AT 5")
        assertEquals(1, collections.memberCountSeenBefore(ALL_SAVED_ID, before = 6))
        assertEquals(2, collections.memberCountSeenBefore(ALL_SAVED_ID, before = 11))
        assertEquals(3, collections.memberCountSeenBefore(ALL_SAVED_ID, before = 21))
        assertEquals(1, collections.memberCountSeenBefore("c1", before = 21), "only that collection's rows")
        assertEquals(0, collections.memberCountSeenBefore("no-such-collection", before = 21))
    }

    /** Strategy A (P3's alternative): the rewrite is limited to the collections this run listed. */
    @Test
    fun deleteRealMembershipsExceptOnlyTouchesKnownCollections() = runTest {
        givenLibrary()
        collections.upsertMemberships(
            listOf(
                CollectionMediaEntity("c2", "m2", sortKey = 30, lastSeenRunId = 1),
                CollectionMediaEntity("c3", "m2", sortKey = 30, lastSeenRunId = 1), // a collection this run did not list
            ),
        )
        suspend fun scopesOfM2() = listOf(ALL_SAVED_ID, "c1", "c2", "c3").filter { collections.memberships(it, listOf("m2")).isNotEmpty() }
        assertEquals(listOf(ALL_SAVED_ID, "c1", "c2", "c3"), scopesOfM2())

        // m2 is now only in c2, as far as the response says; this run listed c1 and c2.
        collections.deleteRealMembershipsExcept("m2", keep = listOf("c2"), known = listOf("c1", "c2"))

        assertEquals(listOf(ALL_SAVED_ID, "c2", "c3"), scopesOfM2(), "c1 went; c2 stays; c3 is not known so it is left alone")
    }

    @Test
    fun deleteRealMembershipsExceptNeverTouchesAllSavedEvenIfListedAsKnown() = runTest {
        givenLibrary()
        collections.deleteRealMembershipsExcept("m1", keep = emptyList(), known = listOf(ALL_SAVED_ID, "c1", "c2"))
        assertEquals(listOf("m1"), collections.memberships(ALL_SAVED_ID, listOf("m1")).map { it.mediaPk })
        assertEquals(emptyList(), collections.memberships("c2", listOf("m1")), "a known real collection is rewritten")
    }

    @Test
    fun deleteRealMembershipsExceptWithNothingKnownDeletesNothing() = runTest {
        givenLibrary()
        collections.deleteRealMembershipsExcept("m2", keep = emptyList(), known = emptyList())
        assertEquals(listOf("m2"), collections.memberships("c1", listOf("m2")).map { it.mediaPk })
    }

    @Test
    fun deleteLibraryKeepsTheRequestLog() = runTest {
        givenLibrary()
        db.apiRequestDao().insert(ApiRequestEntity(at = 1_000))
        val runId = sync.insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = 0))
        sync.upsertCursor(SyncCursorEntity(runId, ALL_SAVED_ID, nextCursor = "c1", walkBase = 1, walkIndex = 0, done = false))
        val allTypes = MediaType.entries.map { it.name }
        val pks = listOf("m1", "m2", "m3", "m4")

        // Sanity: the library is really there before the wipe, including its search index.
        assertEquals(4, media.byPks(pks).size)
        assertEquals(4, collections.memberships(ALL_SAVED_ID, pks).size)
        assertEquals(listOf("m2", "m3", "m1"), media.pageSearch("caption*", allTypes, ALL_SAVED_ID).loadAll().map { it.pk })
        assertEquals(4, ftsIndexedDocuments())
        assertEquals(runId, sync.cursor(runId, ALL_SAVED_ID)!!.runId)

        db.deleteLibrary()

        assertEquals(emptyList(), collections.cards().first())
        assertNull(sync.latestRun())
        assertEquals(emptyList(), media.byPks(pks))
        assertEquals(emptyList(), collections.memberships(ALL_SAVED_ID, pks))
        assertEquals(emptyList(), collections.memberships("c1", pks))
        assertNull(sync.cursor(runId, ALL_SAVED_ID))
        assertEquals(emptyList(), media.pageSearch("caption*", allTypes, ALL_SAVED_ID).loadAll())
        assertEquals(0, ftsIndexedDocuments(), "the wipe must leave no orphaned search index rows")
        assertEquals(1, db.apiRequestDao().countSince(0), "the rolling 24 h budget must survive a library wipe")
    }

    /** Rows in FTS4's `docsize` shadow table: one per indexed document, independent of the content table. */
    private fun ftsIndexedDocuments(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM media_fts_docsize").use {
            it.moveToFirst()
            it.getInt(0)
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
