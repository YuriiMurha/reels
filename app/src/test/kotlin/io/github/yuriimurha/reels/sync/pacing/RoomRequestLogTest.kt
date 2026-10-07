package io.github.yuriimurha.reels.sync.pacing

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class RoomRequestLogTest {
    @Test
    fun countsTheLast24HoursAndPrunesOlderRows() = runTest {
        val db = inMemoryDb()
        val log = RoomRequestLog(db.apiRequestDao())
        log.record(at = 1_000)
        log.record(at = 1_000 + Pacer.DAY_MS)
        assertEquals(1, log.countSince(0), "the row at 1_000 is pruned once a day has passed")
        assertEquals(1_000 + Pacer.DAY_MS, log.oldestSince(0))
        db.close()
    }

    @Test
    fun latestIsTheMostRecentRecordedRequestAcrossRestarts() = runTest {
        val db = inMemoryDb()
        assertNull(RoomRequestLog(db.apiRequestDao()).latest(), "an empty log has no latest request")
        val log = RoomRequestLog(db.apiRequestDao())
        log.record(at = 1_000)
        log.record(at = 5_000)
        log.record(at = 3_000)
        // A new RoomRequestLog over the same table is what a restarted process sees.
        assertEquals(5_000L, RoomRequestLog(db.apiRequestDao()).latest())
        db.close()
    }
}
