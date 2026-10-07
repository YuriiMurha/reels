package io.github.yuriimurha.reels.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.flow.first

/** Where sync runs execute. WorkManager in the app; a fake in tests. */
interface SyncScheduler {
    fun enqueue(runId: Long)
    fun cancel()
    suspend fun isActive(): Boolean
}

/** The unique work request for run [runId] of the library [backendKind] (see [SyncWorker.kindOf]). */
fun syncWorkRequest(runId: Long, backendKind: String): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<SyncWorker>().setInputData(syncInputData(runId, backendKind)).build()

class WorkManagerSyncScheduler(private val context: Context, private val backendKind: String) : SyncScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    /** KEEP: while a sync is queued or running, another enqueue is a no-op (spec 7.1). */
    override fun enqueue(runId: Long) {
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, syncWorkRequest(runId, backendKind))
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_WORK)
    }

    /** Like [cancel], but returns only once WorkManager has recorded the cancellation (Mock mode switch, R67). */
    suspend fun cancelAndAwait() {
        workManager.cancelUniqueWork(UNIQUE_WORK).await()
    }

    override suspend fun isActive(): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK).first().any { !it.state.isFinished }

    companion object {
        const val UNIQUE_WORK = "sync"
    }
}
