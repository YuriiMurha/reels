package io.github.yuriimurha.reels.ui.sync

import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncUiStateTest {
    private val now = 1_000_000L

    private fun run(status: SyncStatus, error: String? = null) =
        SyncRunEntity(mode = SyncMode.FULL, status = status, startedAt = 0, lastError = error)

    private fun pacer(cooldownUntil: Long? = null) = PacerStatus(10, 600, 300, cooldownUntil)

    @Test
    fun nothingYet() {
        val ui = syncUiState(null, pacer(), now)
        assertTrue(ui.canStart)
        assertFalse(ui.resumable)
        assertNull(ui.banner)
    }

    @Test
    fun runningAllowsOnlyCancel() {
        val ui = syncUiState(run(SyncStatus.RUNNING), pacer(), now)
        assertFalse(ui.canStart)
        assertTrue(ui.canCancel)
        assertFalse(ui.canDiscard)
        assertFalse(ui.canDeleteLibrary)
    }

    @Test
    fun pausedRunOffersResumeAndDiscardWithItsReason() {
        val ui = syncUiState(run(SyncStatus.PAUSED, "24-hour budget reached"), pacer(), now)
        assertTrue(ui.resumable)
        assertTrue(ui.canStart)
        assertTrue(ui.canDiscard)
        assertEquals("24-hour budget reached", ui.banner)
    }

    @Test
    fun cooldownBlocksStartingAndCountsDown() {
        val ui = syncUiState(run(SyncStatus.STOPPED_RATE_LIMIT), pacer(cooldownUntil = now + 90_000), now)
        assertFalse(ui.canStart)
        assertEquals("Cooling down after a rate limit: 2 min left", ui.banner)
    }

    @Test
    fun stoppedRunsExplainWhatToDo() {
        assertEquals(
            "Instagram wants verification. Resolve it before syncing again.",
            syncUiState(run(SyncStatus.STOPPED_CHALLENGE), pacer(), now).banner,
        )
        assertEquals(
            "Session expired. Log in again, then tap Resume.",
            syncUiState(run(SyncStatus.STOPPED_LOGIN), pacer(), now).banner,
        )
        assertEquals(
            "Adapter needs repair: items[0].code",
            syncUiState(run(SyncStatus.STOPPED_SHAPE, "Adapter needs repair: items[0].code"), pacer(), now).banner,
        )
    }
}
