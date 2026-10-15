package io.github.yuriimurha.reels.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert
    suspend fun insertRun(run: SyncRunEntity): Long

    /** Writes every column, the status and the error included: the run's start and end, and the buttons' resume and discard. */
    @Update
    suspend fun updateRun(run: SyncRunEntity)

    /**
     * Only the phase and the counts (see [SyncRunProgress]): what the engine writes while a run goes on. It leaves the status
     * and the error alone, so a Cancel that paused the row while a page was committing is not undone by that page's write.
     */
    @Update(entity = SyncRunEntity::class)
    suspend fun updateProgress(progress: SyncRunProgress)

    @Query("SELECT * FROM sync_run WHERE id = :id")
    suspend fun run(id: Long): SyncRunEntity?

    @Query("SELECT * FROM sync_run ORDER BY id DESC LIMIT 1")
    suspend fun latestRun(): SyncRunEntity?

    @Query("SELECT * FROM sync_run ORDER BY id DESC LIMIT 1")
    fun latestRunFlow(): Flow<SyncRunEntity?>

    @Query("SELECT MAX(finishedAt) FROM sync_run WHERE status = 'DONE'")
    fun lastSyncAt(): Flow<Long?>

    @Query("SELECT MAX(finishedAt) FROM sync_run WHERE status = 'DONE' AND mode = 'FULL'")
    fun lastFullSyncAt(): Flow<Long?>

    /** When the latest DONE run (either mode) finished, or null if none has: the end of the library as last confirmed (R83). */
    @Query("SELECT MAX(finishedAt) FROM sync_run WHERE status = 'DONE'")
    suspend fun lastDoneAt(): Long?

    @Query("UPDATE sync_run SET status = 'PAUSED', lastError = :reason WHERE status = 'RUNNING'")
    suspend fun pauseRunningRuns(reason: String)

    @Query("SELECT * FROM sync_cursor WHERE runId = :runId AND scope = :scope")
    suspend fun cursor(runId: Long, scope: String): SyncCursorEntity?

    @Upsert
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_run")
    suspend fun deleteAllRuns()

    @Query("DELETE FROM sync_cursor")
    suspend fun deleteAllCursors()
}
