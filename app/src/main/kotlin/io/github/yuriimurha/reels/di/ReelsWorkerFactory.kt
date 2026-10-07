package io.github.yuriimurha.reels.di

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.github.yuriimurha.reels.sync.SyncWorker

class ReelsWorkerFactory(private val container: () -> AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SyncWorker::class.java.name -> {
            val container = container()
            SyncWorker(appContext, workerParameters, container.syncEngine()::run, SyncWorker.kindOf(container.usesFake))
        }
        else -> null
    }
}
