package io.github.yuriimurha.reels.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one sync in the foreground so it survives leaving the app. The outcome lives in `sync_run`. [runSync] is the engine's
 * `run`; [backendKind] is what this process runs on ([kindOf]): work queued for the other library is refused (R67), because
 * its run id means something else here and both libraries number their runs from 1.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val runSync: suspend (runId: Long) -> Unit,
    private val backendKind: String,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runId = inputData.getLong(KEY_RUN_ID, -1)
        if (runId < 0) return Result.failure()
        // Also refuses work queued before the kind existed: it cannot say which library it belongs to.
        if (inputData.getString(KEY_BACKEND) != backendKind) {
            Log.w("SyncWorker", "Refusing a sync that was queued for the other library")
            return Result.failure()
        }
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Started while the app was in the background: run without the notification.
            Log.w("SyncWorker", "Running without a foreground notification", e)
        }
        runSync(runId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = SyncNotifications.foregroundInfo(applicationContext)

    companion object {
        const val KEY_RUN_ID = "runId"
        const val KEY_BACKEND = "backend"

        /** `"fake"` or `"real"`: which library a run belongs to. */
        fun kindOf(usesFake: Boolean): String = if (usesFake) "fake" else "real"
    }
}

/** The work input: the run, and the library it belongs to. */
fun syncInputData(runId: Long, backendKind: String): Data =
    workDataOf(SyncWorker.KEY_RUN_ID to runId, SyncWorker.KEY_BACKEND to backendKind)
