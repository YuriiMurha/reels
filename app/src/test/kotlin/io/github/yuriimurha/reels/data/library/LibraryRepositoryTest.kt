package io.github.yuriimurha.reels.data.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.CollectionMediaEntity
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.loadAll
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val db = inMemoryDb()
    @get:Rule
    val tmp = TemporaryFolder()

    private val thumbs by lazy { ThumbnailStore(File(tmp.root, "thumbs")) }
    private val repository by lazy { LibraryRepository(db, thumbs) }

    @After
    fun close() = db.close()

    private suspend fun givenLibrary() {
        db.collectionDao().upsert(
            listOf(
                CollectionEntity(ALL_SAVED_ID, "All Saved", null, -1),
                CollectionEntity("c1", "Workouts", null, 0),
                CollectionEntity("c2", "Recipes", null, 1),
            ),
        )
        db.mediaDao().upsert(
            listOf(
                mediaEntity("m1", MediaType.REEL, author = "chef_anna", caption = "Quick pasta"),
                mediaEntity("m2", MediaType.IMAGE, author = "coach_bo", caption = "Leg day plan"),
                mediaEntity("m3", MediaType.CAROUSEL, author = "wanderer", caption = "Lisbon sunset"),
            ),
        )
        db.collectionDao().upsertMemberships(
            listOf(
                CollectionMediaEntity(ALL_SAVED_ID, "m1", 3, 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m2", 2, 1),
                CollectionMediaEntity(ALL_SAVED_ID, "m3", 1, 1),
                CollectionMediaEntity("c2", "m1", 3, 1),
                CollectionMediaEntity("c1", "m2", 2, 1),
            ),
        )
        db.mediaDao().refreshCollectionNames()
    }

    private suspend fun search(text: String, filter: TypeFilter = TypeFilter.ALL, scope: String = ALL_SAVED_ID) =
        repository.pagingSource(MediaSource.Search(FtsQuery.from(text)!!, filter, scope)).loadAll().map { it.pk }

    @Test
    fun cardsListAllSavedThenUncategorizedThenCollections() = runTest {
        givenLibrary()
        val cards = repository.collectionCards().first()
        assertEquals(listOf(ALL_SAVED_ID, UNCATEGORIZED_ID, "c1", "c2"), cards.map { it.id })
        assertEquals(1, cards[1].count)
    }

    @Test
    fun noCardsBeforeTheFirstSync() = runTest {
        assertEquals(emptyList(), repository.collectionCards().first())
    }

    @Test
    fun searchMatchesCaptionAuthorAndCollectionName() = runTest {
        givenLibrary()
        assertEquals(listOf("m1"), search("pasta"))
        assertEquals(listOf("m2"), search("coach"))
        assertEquals(listOf("m1"), search("recipes"))
        assertEquals(listOf("m3"), search("lis"))
    }

    @Test
    fun searchHonoursTypeFilterAndScope() = runTest {
        givenLibrary()
        assertEquals(listOf("m2"), search("plan", TypeFilter.POSTS))
        assertEquals(listOf("m1"), search("pasta", TypeFilter.REELS))
        assertEquals(emptyList(), search("pasta", TypeFilter.POSTS))
        assertEquals(listOf("m1"), search("pasta", scope = "c2"))
        assertEquals(emptyList(), search("pasta", scope = "c1"))
    }

    @Test
    fun searchWithHostileInputDoesNotCrash() = runTest {
        givenLibrary()
        for (input in listOf("\"OR -pasta*", "NEAR(pasta)", "pasta AND", "'; DROP TABLE media; --")) {
            val match = FtsQuery.from(input) ?: continue
            repository.pagingSource(MediaSource.Search(match, TypeFilter.ALL, ALL_SAVED_ID)).loadAll()
        }
    }

    @Test
    fun uncategorizedSourceListsItemsWithoutCollections() = runTest {
        givenLibrary()
        assertEquals(listOf("m3"), repository.pagingSource(MediaSource.Uncategorized).loadAll().map { it.pk })
    }

    @Test
    fun deleteLibraryRemovesRowsAndThumbnails() = runTest {
        givenLibrary()
        val path = thumbs.write("m1", byteArrayOf(1))
        repository.deleteLibrary()
        assertEquals(emptyList(), repository.collectionCards().first())
        assertFalse(File(path).exists())
    }

    @Test
    fun deleteLibraryClearsTheVideoCacheToo() = runTest {
        givenLibrary()
        var cleared = 0
        val repository = LibraryRepository(db, thumbs, clearVideoCache = { cleared++ })

        repository.deleteLibrary()

        assertEquals(1, cleared)
        assertEquals(emptyList(), repository.collectionCards().first())
    }

    /**
     * The library is already gone when the cache is cleared: a cache that cannot be emptied must not make Delete library fail
     * (it is not "couldn't delete the library"), but it is reported, so the owner can tell some cached files are still there.
     */
    @Test
    fun aVideoCacheThatCannotBeClearedDoesNotFailDeleteLibraryButIsReported() = runTest {
        givenLibrary()
        val path = thumbs.write("m1", byteArrayOf(1))
        val repository = LibraryRepository(db, thumbs, clearVideoCache = { throw java.io.IOException("disk error") })

        assertEquals(LibraryDeletion.CACHED_FILES_KEPT, repository.deleteLibrary())

        assertEquals(emptyList(), repository.collectionCards().first(), "the rows are gone")
        assertFalse(File(path).exists(), "and the thumbnails")
    }

    /** Thumbnails that cannot be removed are reported the same way, and the video cache is still cleared. */
    @Test
    fun thumbnailsThatCannotBeRemovedAreReportedAndTheVideoCacheIsStillCleared() = runTest {
        givenLibrary()
        thumbs.write("m1", byteArrayOf(1))
        var cleared = 0
        val repository = LibraryRepository(db, thumbs, clearVideoCache = { cleared++ })
        val dir = File(thumbs.write("m2", byteArrayOf(2))).parentFile!!
        assertTrue(dir.setWritable(false), "precondition: a folder whose files cannot be removed")
        try {
            assertEquals(LibraryDeletion.CACHED_FILES_KEPT, repository.deleteLibrary())
        } finally {
            dir.setWritable(true)
        }

        assertEquals(emptyList(), repository.collectionCards().first(), "the rows are gone")
        assertEquals(1, cleared, "the video cache was cleared all the same")
    }

    @Test
    fun anAccountRecordAndCachedFilesThatBothFailAreBothReported() = runTest {
        givenLibrary()
        val repository = LibraryRepository(
            db, thumbs,
            clearVideoCache = { throw java.io.IOException("disk error") },
            forgetAccount = { throw java.io.IOException("disk full") },
        )

        assertEquals(LibraryDeletion.ACCOUNT_RECORD_AND_CACHED_FILES_KEPT, repository.deleteLibrary())
    }

    /** R84: Delete library also forgets which Instagram account the library belonged to, once its rows are gone. */
    @Test
    fun deleteLibraryForgetsTheLibrarysAccountAfterTheRowsAreGone() = runTest {
        givenLibrary()
        val cardsWhenForgotten = mutableListOf<Int>()
        lateinit var repository: LibraryRepository
        repository = LibraryRepository(db, thumbs, forgetAccount = { cardsWhenForgotten += repository.collectionCards().first().size })

        repository.deleteLibrary()

        assertEquals(listOf(0), cardsWhenForgotten, "forgotten exactly once, after the library itself was deleted")
    }

    /**
     * H4: forgetting the account is a settings write that can fail after the rows are gone. Delete library must not throw
     * (it would crash the app from the ViewModel's scope), must still remove everything else, and must say what was left undone.
     */
    @Test
    fun anAccountRecordThatCannotBeClearedIsReportedNotThrownAndTheRestStillGoes() = runTest {
        givenLibrary()
        val path = thumbs.write("m1", byteArrayOf(1))
        var cleared = 0
        val repository = LibraryRepository(
            db, thumbs,
            clearVideoCache = { cleared++ },
            forgetAccount = { throw java.io.IOException("disk full") },
        )

        assertEquals(LibraryDeletion.ACCOUNT_RECORD_KEPT, repository.deleteLibrary())

        assertEquals(emptyList(), repository.collectionCards().first(), "the rows are gone")
        assertFalse(File(path).exists(), "the thumbnails are gone too: the failure came after the rows, and must not leave orphans")
        assertEquals(1, cleared, "and the cached videos")
    }

    /**
     * Delete library is how the owner switches accounts: the WebView transport's page (and what it remembers about a login or a
     * challenge) belongs to the old session, so it is told first, before anything is wiped.
     */
    @Test
    fun deleteLibraryDestroysThePageFirst() = runTest {
        givenLibrary()
        val cardsWhenTold = mutableListOf<Int>()
        lateinit var repository: LibraryRepository
        repository = LibraryRepository(db, thumbs, beforeSessionChange = { cardsWhenTold += repository.collectionCards().first().size })

        repository.deleteLibrary()

        assertEquals(1, cardsWhenTold.size, "told exactly once")
        assertTrue(cardsWhenTold.single() > 0, "told while the library was still there: first")
    }

    @Test
    fun aPageThatCannotBeDestroyedNeverStopsDeleteLibrary() = runTest {
        givenLibrary()
        val repository = LibraryRepository(db, thumbs, beforeSessionChange = { throw IllegalStateException("the WebView is gone") })

        assertEquals(LibraryDeletion.COMPLETE, repository.deleteLibrary())

        assertEquals(emptyList(), repository.collectionCards().first())
    }

    /** The failure is swallowed, but a debug build is told its class (never its message, which could hold anything). */
    @Test
    fun aPageThatCannotBeDestroyedIsLoggedByClassNameOnly() = runTest {
        givenLibrary()
        val lines = mutableListOf<String>()
        val repository = LibraryRepository(
            db, thumbs,
            beforeSessionChange = { throw IllegalStateException("the WebView is gone: secret-detail") },
            debugLog = lines::add,
        )

        assertEquals(LibraryDeletion.COMPLETE, repository.deleteLibrary())

        assertEquals(emptyList(), repository.collectionCards().first(), "the library is gone")
        assertEquals(listOf("transport reset failed: IllegalStateException"), lines)
    }

    @Test
    fun aResetThatWorksLogsNothing() = runTest {
        givenLibrary()
        val lines = mutableListOf<String>()
        val repository = LibraryRepository(db, thumbs, beforeSessionChange = {}, debugLog = lines::add)

        assertEquals(LibraryDeletion.COMPLETE, repository.deleteLibrary())

        assertEquals(emptyList(), lines)
    }

    @Test
    fun aCancelledHookStillPropagatesTheCancellation() = runTest {
        givenLibrary()
        val repository = LibraryRepository(db, thumbs, beforeSessionChange = { throw kotlin.coroutines.cancellation.CancellationException("cancelled") })

        kotlin.test.assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { repository.deleteLibrary() }
        assertTrue(repository.collectionCards().first().isNotEmpty(), "nothing was deleted")
    }

    @Test
    fun aCompleteDeleteSaysSo() = runTest {
        givenLibrary()
        assertEquals(LibraryDeletion.COMPLETE, repository.deleteLibrary())
    }

    @Test
    fun aCancelledAccountClearStillPropagatesTheCancellation() = runTest {
        givenLibrary()
        val repository = LibraryRepository(
            db, thumbs,
            forgetAccount = { throw kotlin.coroutines.cancellation.CancellationException("cancelled") },
        )

        kotlin.test.assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { repository.deleteLibrary() }
    }

    @Test
    fun aCancelledClearStillPropagatesTheCancellation() = runTest {
        givenLibrary()
        val repository = LibraryRepository(db, thumbs, clearVideoCache = { throw kotlin.coroutines.cancellation.CancellationException("cancelled") })

        kotlin.test.assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { repository.deleteLibrary() }
    }

    @Test
    fun mediaSourceSurvivesEncoding() {
        for (source in listOf(
            MediaSource.Collection("c1"),
            MediaSource.Uncategorized,
            MediaSource.Search("leg* day*", TypeFilter.REELS, "c2"),
        )) {
            assertEquals(source, MediaSource.decode(source.encode()))
        }
        assertTrue(MediaSource.Collection("c1").encode().isNotBlank())
    }
}
