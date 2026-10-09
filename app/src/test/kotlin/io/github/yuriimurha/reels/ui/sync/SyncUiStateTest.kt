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

    /** Spec 2026-10-09 §3.3: the names are the last good ones; said beside whatever the run's banner says, never instead of it. */
    @Test
    fun staleCollectionNamesAreSaid() {
        assertNull(syncUiState(run(SyncStatus.DONE), pacer(), now).collectionNamesNotice)
        assertNull(syncUiState(run(SyncStatus.DONE), pacer(), now, collectionNamesStale = false).collectionNamesNotice)
        val stale = syncUiState(run(SyncStatus.DONE), pacer(), now, collectionNamesStale = true)
        assertEquals("Couldn't refresh collection names", stale.collectionNamesNotice)
        assertNull(stale.banner)
        assertTrue(stale.canStart, "it blocks nothing")

        val paused = syncUiState(run(SyncStatus.PAUSED, "24-hour budget reached"), pacer(), now, collectionNamesStale = true)
        assertEquals("24-hour budget reached", paused.banner)
        assertEquals("Couldn't refresh collection names", paused.collectionNamesNotice)
    }

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

    @Test
    fun sessionNotReadyDisablesStartAndExplains() {
        val ui = syncUiState(null, pacer(), now, sessionReady = false)
        assertFalse(ui.canStart)
        assertEquals("Log in to Instagram to sync", ui.banner)
        assertTrue(ui.canDeleteLibrary, "managing the local library needs no session")
    }

    @Test
    fun aSessionIsReadyByDefault() {
        val ui = syncUiState(null, pacer(), now)
        assertTrue(ui.canStart)
        assertNull(ui.banner)
        assertTrue(syncUiState(null, pacer(), now, sessionReady = true).canStart)
    }

    @Test
    fun otherBannersWinOverTheLoginHint() {
        assertEquals(
            "Cooling down after a rate limit: 2 min left",
            syncUiState(null, pacer(cooldownUntil = now + 90_000), now, sessionReady = false).banner,
        )
        assertEquals(
            "Session expired. Log in again, then tap Resume.",
            syncUiState(run(SyncStatus.STOPPED_LOGIN), pacer(), now, sessionReady = false).banner,
        )
        assertEquals("24-hour budget reached", syncUiState(run(SyncStatus.PAUSED, "24-hour budget reached"), pacer(), now, sessionReady = false).banner)
    }

    @Test
    fun aPausedRunCanBeDiscardedButNotResumedWithoutASession() {
        val ui = syncUiState(run(SyncStatus.PAUSED, "Cancelled"), pacer(), now, sessionReady = false)
        assertTrue(ui.resumable)
        assertFalse(ui.canStart, "Resume is a request to Instagram too")
        assertTrue(ui.canDiscard)
    }

    @Test
    fun aRunningSyncCanStillBeCancelledWhateverTheSessionSays() {
        val ui = syncUiState(run(SyncStatus.RUNNING), pacer(), now, sessionReady = false)
        assertTrue(ui.canCancel)
        assertFalse(ui.canStart)
    }

    @Test
    fun aSessionStillLoadingShowsNoBannerButStaysDisabled() {
        val ui = syncUiState(null, pacer(), now, sessionReady = false, sessionLoading = true)
        assertFalse(ui.canStart, "nothing may start on a guess")
        assertNull(ui.banner, "\"Log in\" would be wrong for a session that is merely not read yet")
    }

    @Test
    fun aLoadingSessionStillLetsAMoreSpecificBannerThrough() {
        assertEquals(
            "24-hour budget reached",
            syncUiState(run(SyncStatus.PAUSED, "24-hour budget reached"), pacer(), now, sessionReady = false, sessionLoading = true).banner,
        )
    }

    // ---- H2: the REAL Pacer's line, shown in Mock mode ----

    @Test
    fun theRealPacersLineCountsTheRequestsOfTheLast24Hours() {
        assertEquals("Instagram requests in 24 h: 10 / 600", realPacerLine(pacer(), now))
    }

    @Test
    fun theRealPacersLineSaysRequestsArePausedDuringACooldown() {
        assertEquals("Instagram requests paused: 2 min left (cooldown)", realPacerLine(pacer(cooldownUntil = now + 90_000), now))
    }

    @Test
    fun theRealPacersMinutesRoundUpLikeTheCooldownBanner() {
        assertEquals("Instagram requests paused: 1 min left (cooldown)", realPacerLine(pacer(cooldownUntil = now + 60_000), now))
        assertEquals("Instagram requests paused: 2 min left (cooldown)", realPacerLine(pacer(cooldownUntil = now + 60_001), now))
        assertEquals("Instagram requests paused: 1 min left (cooldown)", realPacerLine(pacer(cooldownUntil = now + 1), now))
    }

    @Test
    fun aCooldownThatHasEndedFallsBackToTheCount() {
        assertEquals("Instagram requests in 24 h: 10 / 600", realPacerLine(pacer(cooldownUntil = now), now))
        assertEquals("Instagram requests in 24 h: 10 / 600", realPacerLine(pacer(cooldownUntil = now - 1), now))
    }

    @Test
    fun theRealPacersLineUsesThePacersOwnBudgetNotAConstant() {
        assertEquals("Instagram requests in 24 h: 3 / 50", realPacerLine(PacerStatus(3, 50, 25, null), now))
    }

    @Test
    fun noRealPacersLineBeforeItsStatusHasBeenRead() {
        assertNull(realPacerLine(null, now))
    }
}
