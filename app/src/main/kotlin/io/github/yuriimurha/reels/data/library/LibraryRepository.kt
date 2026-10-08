package io.github.yuriimurha.reels.data.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.data.db.CollectionEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** How far [LibraryRepository.deleteLibrary] got. Either way the items, thumbnails and cached videos are gone. */
enum class LibraryDeletion {
    COMPLETE,

    /** The settings write that forgets the library's account failed: the next run still checks against the old account. Delete library again. */
    ACCOUNT_RECORD_KEPT,
}

/** Read side of the library for the UI. */
class LibraryRepository(
    private val db: ReelsDatabase,
    private val thumbnails: ThumbnailStore,
    /** Empties the video cache with the library: cached videos belong to items that no longer exist (spec 8.3). */
    private val clearVideoCache: () -> Unit = {},
    /** R84: forgets the library's account (`LibraryAccount.forget`). */
    private val forgetAccount: suspend () -> Unit = {},
) {
    private val mediaDao = db.mediaDao()
    private val collectionDao = db.collectionDao()

    /** All Saved, then Uncategorized, then real collections; empty until the first sync creates All Saved. */
    fun collectionCards(): Flow<List<CollectionCard>> = combine(
        collectionDao.cards(),
        mediaDao.uncategorizedCount(),
        mediaDao.uncategorizedCover(),
    ) { cards, uncategorizedCount, uncategorizedCover ->
        val allSaved = cards.filter { it.id == ALL_SAVED_ID }
        if (allSaved.isEmpty()) {
            emptyList()
        } else {
            allSaved +
                CollectionCard(UNCATEGORIZED_ID, "Uncategorized", uncategorizedCount, uncategorizedCover) +
                cards.filter { it.id != ALL_SAVED_ID }
        }
    }

    fun pagingSource(source: MediaSource): PagingSource<Int, MediaEntity> = when (source) {
        is MediaSource.Collection -> mediaDao.pageCollection(source.id)
        MediaSource.Uncategorized -> mediaDao.pageUncategorized()
        is MediaSource.Search -> mediaDao.pageSearch(source.match, source.filter.types.map { it.name }, source.scope)
    }

    /** Placeholders are on, so list positions are absolute and the viewer can open at [initialIndex]. */
    fun pager(source: MediaSource, initialIndex: Int = 0): Flow<PagingData<MediaEntity>> =
        Pager(
            config = PagingConfig(pageSize = 60, enablePlaceholders = true),
            initialKey = initialIndex,
            pagingSourceFactory = { pagingSource(source) },
        ).flow

    fun liveCollections(): Flow<List<CollectionEntity>> = collectionDao.liveCollections()

    suspend fun collectionsOf(pk: String): List<CollectionEntity> = collectionDao.collectionsOf(pk)

    /**
     * Wipes synced items, collections, history, thumbnails and cached videos, and forgets which Instagram account the library
     * belonged to (R84), so the next sync may be another account's. Keeps the session and the request log (spec 9.5). The
     * account is forgotten only after the rows are gone: the other order could leave a library with no owner to check against.
     *
     * Forgetting the account is a settings write and can fail after the rows are gone. That must not stop the rest (thumbnails
     * and cached videos would be left orphaned) and must not escape to the caller's scope, where it would crash the app: it is
     * reported as [LibraryDeletion.ACCOUNT_RECORD_KEPT] instead, and a second Delete library finishes the job. Cancellation
     * still propagates. A failure of the delete itself still throws, for the caller to say so.
     */
    suspend fun deleteLibrary(): LibraryDeletion {
        db.deleteLibrary()
        var result = LibraryDeletion.COMPLETE
        try {
            forgetAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result = LibraryDeletion.ACCOUNT_RECORD_KEPT
        }
        withContext(Dispatchers.IO) {
            thumbnails.deleteAll()
            try {
                clearVideoCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The library is already gone; cached videos only take space until the cache evicts them.
            }
        }
        return result
    }
}
