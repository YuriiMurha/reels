package io.github.yuriimurha.reels.sync.pacing

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

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
}
