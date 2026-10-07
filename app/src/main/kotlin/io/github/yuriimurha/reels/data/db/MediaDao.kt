package io.github.yuriimurha.reels.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Spec 5.3 "Uncategorized": live items saved to All Saved that belong to no live real collection.
 * Shared by [MediaDao.pageUncategorized], [MediaDao.uncategorizedCount] and [MediaDao.uncategorizedCover];
 * `m` is the media row and `cm` its All Saved membership, which carries the sort key.
 */
private const val UNCATEGORIZED_FROM_WHERE = """
    FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = '$ALL_SAVED_ID'
    WHERE m.removedAt IS NULL AND NOT EXISTS (
        SELECT 1 FROM collection_media x JOIN collection c ON c.id = x.collectionId
        WHERE x.mediaPk = m.pk AND x.collectionId != '$ALL_SAVED_ID' AND c.removedAt IS NULL)"""

/**
 * The value `media.collectionNames` must hold for the enclosing `media` row: the space-joined,
 * name-ordered names of its live real collections, or '' when it has none.
 */
private const val LIVE_COLLECTION_NAMES = """COALESCE((
        SELECT GROUP_CONCAT(name, ' ') FROM (
            SELECT c.name AS name FROM collection_media cm JOIN collection c ON c.id = cm.collectionId
            WHERE cm.mediaPk = media.pk AND cm.collectionId != '$ALL_SAVED_ID' AND c.removedAt IS NULL
            ORDER BY c.name)), '')"""

/** A media row that still needs its thumbnail fetched from [url]. */
data class ThumbnailTarget(val pk: String, val url: String)

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE pk IN (:pks)")
    suspend fun byPks(pks: List<String>): List<MediaEntity>

    @Upsert
    suspend fun upsert(media: List<MediaEntity>)

    @Query("UPDATE media SET thumbPath = :path WHERE pk = :pk")
    suspend fun setThumbPath(pk: String, path: String?)

    /** Live items of [scope] that run [runId] saw but has no thumbnail for: what an interrupted page leaves behind. */
    @Query(
        """
        SELECT m.pk AS pk, m.thumbUrl AS url FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk
        WHERE cm.collectionId = :scope AND cm.lastSeenRunId = :runId AND m.thumbPath IS NULL AND m.removedAt IS NULL
        """,
    )
    suspend fun withoutThumbnailSeenIn(scope: String, runId: Long): List<ThumbnailTarget>

    /** A refreshed video link (spec 8.2). */
    @Query("UPDATE media SET videoUrl = :url, videoUrlExpiresAt = :expiresAt WHERE pk = :pk")
    suspend fun setVideoLink(pk: String, url: String?, expiresAt: Long?)

    @Query("UPDATE media SET removedAt = :at, thumbPath = NULL WHERE pk IN (:pks)")
    suspend fun markRemoved(pks: List<String>, at: Long)

    @Query(
        """
        UPDATE media SET collectionNames = $LIVE_COLLECTION_NAMES
        WHERE collectionNames != $LIVE_COLLECTION_NAMES
        """,
    )
    suspend fun refreshCollectionNames()

    @Query(
        """
        SELECT m.* FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk
        WHERE cm.collectionId = :collectionId AND m.removedAt IS NULL
        ORDER BY cm.sortKey DESC
        """,
    )
    fun pageCollection(collectionId: String): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT m.* $UNCATEGORIZED_FROM_WHERE
        ORDER BY cm.sortKey DESC
        """,
    )
    fun pageUncategorized(): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT COUNT(*) $UNCATEGORIZED_FROM_WHERE
        """,
    )
    fun uncategorizedCount(): Flow<Int>

    @Query(
        """
        SELECT m.thumbPath $UNCATEGORIZED_FROM_WHERE
        ORDER BY cm.sortKey DESC LIMIT 1
        """,
    )
    fun uncategorizedCover(): Flow<String?>

    /** [match] must come from FtsQuery.from; [types] are MediaType names. */
    @Query(
        """
        SELECT m.* FROM media m
        JOIN media_fts ON media_fts.rowid = m.rowid
        JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = :scope
        WHERE media_fts MATCH :match AND m.removedAt IS NULL AND m.type IN (:types)
        ORDER BY cm.sortKey DESC
        """,
    )
    fun pageSearch(match: String, types: List<String>, scope: String): PagingSource<Int, MediaEntity>

    @Query("DELETE FROM media")
    suspend fun deleteAll()
}
