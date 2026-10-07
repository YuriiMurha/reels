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

/** Read side of the library for the UI. */
class LibraryRepository(
    private val db: ReelsDatabase,
    private val thumbnails: ThumbnailStore,
    /** Empties the video cache with the library: cached videos belong to items that no longer exist (spec 8.3). */
    private val clearVideoCache: () -> Unit = {},
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

    /** Wipes synced items, collections, history, thumbnails and cached videos. Keeps the session and the request log (spec 9.5). */
    suspend fun deleteLibrary() {
        db.deleteLibrary()
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
    }
}
