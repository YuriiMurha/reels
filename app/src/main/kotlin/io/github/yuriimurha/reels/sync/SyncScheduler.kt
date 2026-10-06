package io.github.yuriimurha.reels.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first

/** Where sync runs execute. WorkManager in the app; a fake in tests. */
interface SyncScheduler {
    fun enqueue(runId: Long)
    fun cancel()
    suspend fun isActive(): Boolean
}

class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    /** KEEP: while a sync is queued or running, another enqueue is a no-op (spec 7.1). */
    override fun enqueue(runId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_RUN_ID to runId))
            .build()
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK)
    }

    override suspend fun isActive(): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK).first().any { !it.state.isFinished }

    companion object {
        const val UNIQUE_WORK = "sync"
    }
}
