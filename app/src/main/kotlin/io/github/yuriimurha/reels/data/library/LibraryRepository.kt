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

/** How far [LibraryRepository.deleteLibrary] got. Whatever the answer, the items, collections and history are gone. */
enum class LibraryDeletion {
    COMPLETE,

    /** The settings write that forgets the library's account failed: the next run still checks against the old account. Delete library again. */
    ACCOUNT_RECORD_KEPT,

    /** Some thumbnails or cached videos could not be removed. They only take space. */
    CACHED_FILES_KEPT,

    /** Both of the above. */
    ACCOUNT_RECORD_AND_CACHED_FILES_KEPT,
    ;

    val accountRecordKept: Boolean get() = this == ACCOUNT_RECORD_KEPT || this == ACCOUNT_RECORD_AND_CACHED_FILES_KEPT
    val cachedFilesKept: Boolean get() = this == CACHED_FILES_KEPT || this == ACCOUNT_RECORD_AND_CACHED_FILES_KEPT

    companion object {
        fun of(accountRecordKept: Boolean, cachedFilesKept: Boolean) = when {
            accountRecordKept && cachedFilesKept -> ACCOUNT_RECORD_AND_CACHED_FILES_KEPT
            accountRecordKept -> ACCOUNT_RECORD_KEPT
            cachedFilesKept -> CACHED_FILES_KEPT
            else -> COMPLETE
        }
    }
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
     * Once the rows are gone nothing after them may fail the delete or stop the rest: forgetting the account is a settings write,
     * and a thumbnail or a cached video may not be removable. Each is tried, and what was left is reported in the result
     * ([LibraryDeletion.ACCOUNT_RECORD_KEPT], [LibraryDeletion.CACHED_FILES_KEPT], or both) instead of escaping to the caller's
     * scope, where it would crash the app. A second Delete library finishes the job. Cancellation still propagates. A failure of
     * the delete itself (the rows) still throws, for the caller to say so.
     */
    suspend fun deleteLibrary(): LibraryDeletion {
        db.deleteLibrary()
        var accountRecordKept = false
        try {
            forgetAccount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            accountRecordKept = true
        }
        var cachedFilesKept = false
        withContext(Dispatchers.IO) {
            try {
                thumbnails.deleteAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cachedFilesKept = true
            }
            try {
                clearVideoCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Cached videos only take space until the cache evicts them; still reported, so the owner knows they are there.
                cachedFilesKept = true
            }
        }
        return LibraryDeletion.of(accountRecordKept, cachedFilesKept)
    }
}
