package io.github.yuriimurha.reels.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ApiRequestDao {
    @Insert
    suspend fun insert(request: ApiRequestEntity)

    @Query("SELECT COUNT(*) FROM api_request WHERE at > :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT MIN(at) FROM api_request WHERE at > :since")
    suspend fun oldestSince(since: Long): Long?

    @Query("SELECT MAX(at) FROM api_request")
    suspend fun latest(): Long?

    @Query("DELETE FROM api_request WHERE at <= :before")
    suspend fun deleteUpTo(before: Long)
}
