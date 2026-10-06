package io.github.yuriimurha.reels.ui.common

import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStatusSummaryTest {
    private fun run(status: SyncStatus, error: String? = null) =
        SyncRunEntity(mode = SyncMode.QUICK, status = status, startedAt = 0, lastError = error)

    @Test
    fun runningWins() = assertEquals(SyncStatusSummary.Running, SyncStatusSummary.from(run(SyncStatus.RUNNING), 5))

    @Test
    fun unfinishedRunsShowTheirReason() = assertEquals(
        SyncStatusSummary.Problem("Session expired"),
        SyncStatusSummary.from(run(SyncStatus.STOPPED_LOGIN, "Session expired"), 5),
    )

    @Test
    fun finishedRunsShowTheLastSync() {
        assertEquals(SyncStatusSummary.SyncedAt(5), SyncStatusSummary.from(run(SyncStatus.DONE), 5))
        assertEquals(SyncStatusSummary.SyncedAt(5), SyncStatusSummary.from(run(SyncStatus.CANCELLED), 5))
    }

    @Test
    fun nothingYet() = assertEquals(SyncStatusSummary.Never, SyncStatusSummary.from(null, null))
}
