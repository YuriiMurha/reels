package io.github.yuriimurha.reels.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@RunWith(AndroidJUnit4::class)
class SyncControllerTest {
    private val db = inMemoryDb()
    private val scheduler = FakeScheduler()
    private val controller = SyncController(db, scheduler, now = { 1_000 })

    @After
    fun close() = db.close()

    @Test
    fun startCreatesARunningRunAndEnqueuesIt() = runTest {
        val id = controller.start(SyncMode.QUICK)
        val run = db.syncDao().run(id)!!
        assertEquals(SyncStatus.RUNNING, run.status)
        assertEquals(SyncMode.QUICK, run.mode)
        assertEquals(listOf(id), scheduler.enqueued)
    }

    @Test
    fun sequentialDoubleTapReusesTheRunningRun() = runTest {
        val first = controller.start(SyncMode.QUICK)
        val second = controller.start(SyncMode.FULL)
        assertEquals(first, second)
        assertEquals(1, runCount())
        assertEquals(listOf(first, first), scheduler.enqueued, "the second tap re-enqueues the same run (KEEP makes it a no-op)")
    }

    @Test
    fun concurrentTapsOnAnEmptyTableCreateExactlyOneRun() = runTest {
        assertEquals(0, runCount(), "no earlier call: every start() below races on the insert path")
        val modes = listOf(SyncMode.QUICK, SyncMode.FULL, SyncMode.QUICK, SyncMode.FULL, SyncMode.QUICK, SyncMode.FULL)
        val ids = modes.map { mode -> async(Dispatchers.Default) { controller.start(mode) } }.awaitAll()
        assertEquals(1, ids.toSet().size, "all taps get the same run id: $ids")
        assertEquals(1, runCount())
        assertEquals(modes.size, scheduler.enqueued.size)
        assertEquals(setOf(ids.first()), scheduler.enqueued.toSet())
    }

    @Test
    fun aResumableRunIsResumedEvenFromTheOtherButton() = runTest {
        val paused = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.STOPPED_LOGIN, startedAt = 0))
        val id = controller.start(SyncMode.FULL)
        assertEquals(paused, id)
        val run = db.syncDao().run(id)!!
        assertEquals(SyncStatus.RUNNING, run.status)
        assertEquals(SyncMode.QUICK, run.mode)
    }

    @Test
    fun discardMakesTheNextTapStartFresh() = runTest {
        val paused = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.PAUSED, startedAt = 0))
        controller.discardResumable()
        assertEquals(SyncStatus.CANCELLED, db.syncDao().run(paused)!!.status)
        assertNotEquals(paused, controller.start(SyncMode.QUICK))
    }

    @Test
    fun recoverInterruptedRunsPausesOrphans() = runTest {
        val orphan = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.RUNNING, startedAt = 0))
        scheduler.active = false
        controller.recoverInterruptedRuns()
        val run = db.syncDao().run(orphan)!!
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("Interrupted, tap Sync to resume", run.lastError)
        assertEquals(orphan, controller.start(SyncMode.QUICK), "the orphan resumes from its cursor")
    }

    @Test
    fun recoverLeavesAnActiveRunAlone() = runTest {
        val running = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.RUNNING, startedAt = 0))
        scheduler.active = true
        controller.recoverInterruptedRuns()
        assertEquals(SyncStatus.RUNNING, db.syncDao().run(running)!!.status)
    }

    @Test
    fun cancelStopsTheWorkAndLeavesTheRunResumable() = runTest {
        val id = controller.start(SyncMode.QUICK)
        controller.cancel()
        assertEquals(1, scheduler.cancelled)
        assertEquals(SyncStatus.PAUSED, db.syncDao().run(id)!!.status)
    }

    private fun runCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_run").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private class FakeScheduler : SyncScheduler {
        val enqueued = CopyOnWriteArrayList<Long>()
        var cancelled = 0
        var active = false

        override fun enqueue(runId: Long) {
            enqueued += runId
        }

        override fun cancel() {
            cancelled++
        }

        override suspend fun isActive(): Boolean = active
    }
}
