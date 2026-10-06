package io.github.yuriimurha.reels.sync

import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Sync and Full sync buttons (spec 7.1). */
class SyncController(
    db: ReelsDatabase,
    private val scheduler: SyncScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val syncDao = db.syncDao()
    private val mutex = Mutex()

    val latestRun: Flow<SyncRunEntity?> = syncDao.latestRunFlow()
    val lastSyncAt: Flow<Long?> = syncDao.lastSyncAt()
    val lastFullSyncAt: Flow<Long?> = syncDao.lastFullSyncAt()

    /** Resumes the latest unfinished run (whatever its mode), or starts a new one. Never creates a second run. */
    suspend fun start(mode: SyncMode): Long = mutex.withLock {
        val latest = syncDao.latestRun()
        val id = if (latest != null && (latest.status == SyncStatus.RUNNING || latest.status.isResumable)) {
            if (latest.status != SyncStatus.RUNNING) {
                syncDao.updateRun(latest.copy(status = SyncStatus.RUNNING, lastError = null))
            }
            latest.id
        } else {
            syncDao.insertRun(SyncRunEntity(mode = mode, status = SyncStatus.RUNNING, startedAt = now()))
        }
        scheduler.enqueue(id)
        id
    }

    /**
     * Stops after the current request; the run stays resumable from its cursors. Takes the same lock as [start], so a
     * Cancel followed by Sync can never let a late pause overwrite the run that Sync just resumed.
     */
    suspend fun cancel() {
        mutex.withLock {
            scheduler.cancel()
            syncDao.pauseRunningRuns("Cancelled")
        }
    }

    /** Abandons an unfinished run so the next tap starts fresh. Nothing is deleted (spec 7.1). */
    suspend fun discardResumable() {
        mutex.withLock {
            val latest = syncDao.latestRun() ?: return@withLock
            if (latest.status.isResumable) {
                syncDao.updateRun(latest.copy(status = SyncStatus.CANCELLED, finishedAt = now()))
            }
        }
    }

    /** After process death a RUNNING row can be left with no worker behind it; make it resumable. */
    suspend fun recoverInterruptedRuns() {
        mutex.withLock {
            if (!scheduler.isActive()) syncDao.pauseRunningRuns("Interrupted, tap Sync to resume")
        }
    }
}
