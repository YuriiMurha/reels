package io.github.yuriimurha.reels.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException

/** Runs one sync in the foreground so it survives leaving the app. The outcome lives in `sync_run`. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val runId = inputData.getLong(KEY_RUN_ID, -1)
        if (runId < 0) return Result.failure()
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Started while the app was in the background: run without the notification.
            Log.w("SyncWorker", "Running without a foreground notification", e)
        }
        engine.run(runId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = SyncNotifications.foregroundInfo(applicationContext)

    companion object {
        const val KEY_RUN_ID = "runId"
    }
}
