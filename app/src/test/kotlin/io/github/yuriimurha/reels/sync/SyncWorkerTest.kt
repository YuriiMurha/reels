package io.github.yuriimurha.reels.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.di.ReelsWorkerFactory
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * R67: the work input only names a run id, and both libraries number their runs from 1. After a Mock mode restart,
 * WorkManager may still hold work that was enqueued for the OTHER library; running it would start an unrequested sync
 * (possibly a real, possibly FULL one) on whatever run has that id here.
 */
@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ran = mutableListOf<Long>()

    private fun worker(input: androidx.work.Data, processKind: String): SyncWorker =
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(input)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                        SyncWorker(appContext, workerParameters, { ran += it }, processKind)
                },
            )
            .build()

    @Test
    fun aRunQueuedForTheOtherLibraryIsRefusedWithoutRunning() = runBlocking {
        assertEquals(ListenableWorker.Result.failure(), worker(syncInputData(7, "fake"), processKind = "real").doWork())
        assertEquals(ListenableWorker.Result.failure(), worker(syncInputData(7, "real"), processKind = "fake").doWork())
        assertEquals(emptyList(), ran, "the engine must not be touched")
    }

    @Test
    fun aRunQueuedBeforeTheBackendKindExistedIsRefused() = runBlocking {
        val oldInput = workDataOf(SyncWorker.KEY_RUN_ID to 7L)
        assertEquals(ListenableWorker.Result.failure(), worker(oldInput, processKind = "fake").doWork())
        assertEquals(emptyList(), ran)
    }

    @Test
    fun aRunForThisLibraryRuns() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker(syncInputData(7, "real"), processKind = "real").doWork())
        assertEquals(listOf(7L), ran)
    }

    @Test
    fun theWorkRequestCarriesTheRunIdAndTheBackendKind() {
        val input = syncWorkRequest(5, "real").workSpec.input
        assertEquals(5L, input.getLong(SyncWorker.KEY_RUN_ID, -1))
        assertEquals("real", input.getString(SyncWorker.KEY_BACKEND))
    }

    @Test
    fun theKindFollowsTheMode() {
        assertEquals("fake", SyncWorker.kindOf(usesFake = true))
        assertEquals("real", SyncWorker.kindOf(usesFake = false))
    }

    /** The app's own factory builds workers of the container's kind: a Mock mode process refuses work queued as real. */
    @Test
    fun theAppFactoryGivesTheWorkerTheContainersKind() = runBlocking {
        context.getSharedPreferences("backend", Context.MODE_PRIVATE).edit().putBoolean("use_fake", true).commit()
        val container = AppContainer(context)
        try {
            val worker = TestListenableWorkerBuilder<SyncWorker>(context)
                .setInputData(syncInputData(99, "real")) // no such run here: reaching the engine would throw
                .setWorkerFactory(ReelsWorkerFactory { container })
                .build()
            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        } finally {
            container.db.close()
        }
    }
}
