package io.github.yuriimurha.reels.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.sync.SyncWorker
import io.github.yuriimurha.reels.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

/**
 * R70 (c): `AppContainer`'s private scheduler is the one place that decides which library a queued run belongs to (R67)
 * and what the Mock mode switch waits for. Both wirings used to be pinned only by reading the code, so a wrong boolean or a
 * no-op `cancelSync` compiled and passed every other test. These run the real container on the real WorkManager (the test
 * build of it), with a worker that never finishes so the work stays where WorkManager put it.
 */
@RunWith(AndroidJUnit4::class)
class ContainerSyncWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val containers = mutableListOf<AppContainer>()

    /** The backend kind of every sync work request WorkManager handed to a worker. */
    private val kindsSeen = CopyOnWriteArrayList<String?>()

    /**
     * What the workers wait on. It is held by this test, so a waiting worker stays reachable: a coroutine parked on nothing
     * can be garbage collected, and WorkManager then reports its work as FAILED instead of running.
     */
    private val workerGate = CompletableDeferred<Unit>()

    @Before
    fun startWorkManagerWithAWorkerThatNeverFinishes() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                kindsSeen += workerParameters.inputData.getString(SyncWorker.KEY_BACKEND)
                return object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result {
                        workerGate.await()
                        return Result.success()
                    }
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
    }

    @After
    fun stopTheWorkerAndCloseDatabases() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        workerGate.complete(Unit)
        containers.forEach {
            it.requestLogDb.close()
            it.db.close()
        }
    }

    private fun container(useFake: Boolean): AppContainer {
        context.getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE).edit().putBoolean(BackendChoice.KEY_USE_FAKE, useFake).commit()
        return AppContainer(context).also { containers += it }
    }

    private fun syncWork(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerSyncScheduler.UNIQUE_WORK).get()

    /** A Sync tap through the container's own controller, so the scheduler under test is the container's. */
    private fun queueARun(container: AppContainer) = runBlocking { container.syncController.start(SyncMode.QUICK) }

    /** WorkManager creates the worker on its own executor, so wait (briefly) for the first one instead of assuming it is there. */
    private fun kindsOfWorkersCreated(): List<String?> {
        val deadline = System.nanoTime() + 5_000_000_000
        while (kindsSeen.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        return kindsSeen.toList()
    }

    @Test
    fun aMockModeProcessQueuesRunsStampedFake() {
        queueARun(container(useFake = true))

        assertEquals(listOf<String?>("fake"), kindsOfWorkersCreated(), "a Mock mode run must be refused by a real-mode process (R67)")
    }

    @Test
    fun aRealModeProcessQueuesRunsStampedReal() {
        queueARun(container(useFake = false))

        assertEquals(listOf<String?>("real"), kindsOfWorkersCreated(), "a real run must be refused by a Mock mode process (R67)")
    }

    /** The switch must not restart while WorkManager still holds the run: it would be replayed on the other library. */
    @Test
    fun theMockSwitchCancelsTheQueuedSyncAndWaitsForIt() = runBlocking {
        val container = container(useFake = true)
        queueARun(container)
        assertEquals(1, syncWork().count { !it.state.isFinished }, "precondition: WorkManager holds the run (queued or running): ${syncWork()}")
        var restarted = false

        val changed = container.mockModeSwitch { restarted = true }.change(useFake = false)

        assertEquals(true, changed, "change() returned false")
        assertEquals(true, restarted, "no restart")
        assertEquals(listOf(WorkInfo.State.CANCELLED), syncWork().map { it.state }, "cancelAndAwait must have recorded the cancellation: ${syncWork()}")
    }
}
