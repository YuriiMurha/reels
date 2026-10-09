package io.github.yuriimurha.reels.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Upsert
    suspend fun upsert(collections: List<CollectionEntity>)

    @Query("SELECT * FROM collection WHERE removedAt IS NULL AND id != '$ALL_SAVED_ID' ORDER BY position")
    fun liveCollections(): Flow<List<CollectionEntity>>

    /** [liveCollections], read once: the names a sync keeps when it cannot refresh them (spec 2026-10-09 §3.3). */
    @Query("SELECT * FROM collection WHERE removedAt IS NULL AND id != '$ALL_SAVED_ID' ORDER BY position")
    suspend fun liveCollectionsNow(): List<CollectionEntity>

    @Query("UPDATE collection SET removedAt = :at WHERE id NOT IN (:keep) AND id != '$ALL_SAVED_ID' AND removedAt IS NULL")
    suspend fun markRemovedExcept(keep: List<String>, at: Long)

    @Query("SELECT * FROM collection_media WHERE collectionId = :collectionId AND mediaPk IN (:pks)")
    suspend fun memberships(collectionId: String, pks: List<String>): List<CollectionMediaEntity>

    @Upsert
    suspend fun upsertMemberships(memberships: List<CollectionMediaEntity>)

    @Query("SELECT MAX(sortKey) FROM collection_media WHERE collectionId = :collectionId")
    suspend fun maxSortKey(collectionId: String): Long?

    @Query("SELECT COUNT(*) FROM collection WHERE removedAt IS NULL AND id != '$ALL_SAVED_ID'")
    suspend fun liveCollectionCount(): Int

    /**
     * [collectionId]'s members whose media was first seen before [before] (a run's `startedAt`): the library as it was before
     * that run. Items the run itself adds are first seen at or after its start, so they never enlarge this count.
     */
    @Query(
        "SELECT COUNT(*) FROM collection_media cm JOIN media m ON m.pk = cm.mediaPk WHERE cm.collectionId = :collectionId AND m.firstSeenAt < :before",
    )
    suspend fun memberCountSeenBefore(collectionId: String, before: Long): Int

    /** Strategy A: rewrites [pk]'s memberships only among [known] collections; others are left alone. */
    @Query(
        "DELETE FROM collection_media WHERE mediaPk = :pk AND collectionId != '$ALL_SAVED_ID' AND collectionId IN (:known) AND collectionId NOT IN (:keep)",
    )
    suspend fun deleteRealMembershipsExcept(pk: String, keep: List<String>, known: List<String>)

    @Query("SELECT mediaPk FROM collection_media WHERE collectionId = :collectionId AND lastSeenRunId != :runId")
    suspend fun unseenPks(collectionId: String, runId: Long): List<String>

    @Query("DELETE FROM collection_media WHERE collectionId = :collectionId AND lastSeenRunId != :runId")
    suspend fun deleteUnseen(collectionId: String, runId: Long)

    @Query("DELETE FROM collection_media WHERE mediaPk IN (:pks)")
    suspend fun deleteAllMembershipsOf(pks: List<String>)

    @Query(
        """
        SELECT c.id AS id, c.name AS name,
            (SELECT COUNT(*) FROM collection_media cm JOIN media m ON m.pk = cm.mediaPk
             WHERE cm.collectionId = c.id AND m.removedAt IS NULL) AS count,
            COALESCE(
                (SELECT m.thumbPath FROM media m WHERE m.pk = c.coverPk AND m.removedAt IS NULL),
                (SELECT m.thumbPath FROM collection_media cm JOIN media m ON m.pk = cm.mediaPk
                 WHERE cm.collectionId = c.id AND m.removedAt IS NULL
                 ORDER BY cm.sortKey DESC LIMIT 1)) AS coverThumbPath
        FROM collection c WHERE c.removedAt IS NULL
        ORDER BY CASE WHEN c.id = '$ALL_SAVED_ID' THEN 0 ELSE 1 END, c.position
        """,
    )
    fun cards(): Flow<List<CollectionCard>>

    @Query(
        """
        SELECT c.* FROM collection c JOIN collection_media cm ON cm.collectionId = c.id
        WHERE cm.mediaPk = :pk AND c.id != '$ALL_SAVED_ID' AND c.removedAt IS NULL ORDER BY c.position
        """,
    )
    suspend fun collectionsOf(pk: String): List<CollectionEntity>

    @Query("DELETE FROM collection")
    suspend fun deleteAll()

    @Query("DELETE FROM collection_media")
    suspend fun deleteAllMemberships()
}
