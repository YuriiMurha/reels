package io.github.yuriimurha.reels.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE pk IN (:pks)")
    suspend fun byPks(pks: List<String>): List<MediaEntity>

    @Upsert
    suspend fun upsert(media: List<MediaEntity>)

    @Query("UPDATE media SET thumbPath = :path WHERE pk = :pk")
    suspend fun setThumbPath(pk: String, path: String?)

    @Query("UPDATE media SET removedAt = :at, thumbPath = NULL WHERE pk IN (:pks)")
    suspend fun markRemoved(pks: List<String>, at: Long)

    @Query(
        """
        UPDATE media SET collectionNames = COALESCE((
            SELECT GROUP_CONCAT(name, ' ') FROM (
                SELECT c.name AS name FROM collection_media cm JOIN collection c ON c.id = cm.collectionId
                WHERE cm.mediaPk = media.pk AND cm.collectionId != '__all__' AND c.removedAt IS NULL
                ORDER BY c.name)), '')
        WHERE collectionNames != COALESCE((
            SELECT GROUP_CONCAT(name, ' ') FROM (
                SELECT c.name AS name FROM collection_media cm JOIN collection c ON c.id = cm.collectionId
                WHERE cm.mediaPk = media.pk AND cm.collectionId != '__all__' AND c.removedAt IS NULL
                ORDER BY c.name)), '')
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
        SELECT m.* FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = '__all__'
        WHERE m.removedAt IS NULL AND NOT EXISTS (
            SELECT 1 FROM collection_media x JOIN collection c ON c.id = x.collectionId
            WHERE x.mediaPk = m.pk AND x.collectionId != '__all__' AND c.removedAt IS NULL)
        ORDER BY cm.sortKey DESC
        """,
    )
    fun pageUncategorized(): PagingSource<Int, MediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = '__all__'
        WHERE m.removedAt IS NULL AND NOT EXISTS (
            SELECT 1 FROM collection_media x JOIN collection c ON c.id = x.collectionId
            WHERE x.mediaPk = m.pk AND x.collectionId != '__all__' AND c.removedAt IS NULL)
        """,
    )
    fun uncategorizedCount(): Flow<Int>

    @Query(
        """
        SELECT m.thumbPath FROM media m JOIN collection_media cm ON cm.mediaPk = m.pk AND cm.collectionId = '__all__'
        WHERE m.removedAt IS NULL AND NOT EXISTS (
            SELECT 1 FROM collection_media x JOIN collection c ON c.id = x.collectionId
            WHERE x.mediaPk = m.pk AND x.collectionId != '__all__' AND c.removedAt IS NULL)
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
