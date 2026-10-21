package io.github.yuriimurha.reels.ui.home

import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sync button's badge (2026-10-21), mapped from each state of the status chip it replaced: "Syncing…" turns, "⚠" and a
 * stopped or paused run's reason is a dot, "Synced 5 min ago" and "Not synced" are the plain glyph. Plus the real library's
 * session, which the chip never read.
 */
class SyncBadgeTest {
    private fun run(status: SyncStatus, error: String? = null) =
        SyncRunEntity(mode = SyncMode.QUICK, status = status, startedAt = 0, lastError = error)

    private fun mock(run: SyncRunEntity?) = SyncBadge.from(run, session = null, requiresSession = false)

    private fun real(run: SyncRunEntity?, session: SessionState?) = SyncBadge.from(run, session, requiresSession = true)

    @Test
    fun runningWins() {
        assertEquals(SyncBadge.RUNNING, mock(run(SyncStatus.RUNNING)))
        // Over a session that needs the owner too: the run is what is happening (it stops by itself if the session is gone).
        assertEquals(SyncBadge.RUNNING, real(run(SyncStatus.RUNNING), SessionState.LoggedOut))
        assertEquals(SyncBadge.RUNNING, real(run(SyncStatus.RUNNING), SessionState.Expired("handle")))
    }

    /** The chip's "⚠" and the run's reason, one by one: each is a dot. */
    @Test
    fun unfinishedRunsNeedAttention() {
        assertEquals(SyncBadge.ATTENTION, mock(run(SyncStatus.STOPPED_LOGIN, "Session expired")))
        assertEquals(SyncBadge.ATTENTION, mock(run(SyncStatus.STOPPED_CHALLENGE, "Instagram wants verification")))
        assertEquals(SyncBadge.ATTENTION, mock(run(SyncStatus.STOPPED_SHAPE, "Adapter needs repair: items")))
        assertEquals(SyncBadge.ATTENTION, mock(run(SyncStatus.STOPPED_RATE_LIMIT, "Instagram is limiting requests")))
        assertEquals(SyncBadge.ATTENTION, mock(run(SyncStatus.PAUSED, "Cancelled")))
        assertEquals(SyncBadge.ATTENTION, mock(run(SyncStatus.PAUSED, "Interrupted, tap Resume")))
        assertEquals(SyncBadge.ATTENTION, mock(run(SyncStatus.PAUSED)), "a paused run with no reason (the chip's \"Sync paused\")")
        // A Valid session does not hide a stopped run.
        assertEquals(SyncBadge.ATTENTION, real(run(SyncStatus.STOPPED_SHAPE), SessionState.Valid("handle")))
    }

    /** Exactly the statuses the chip warned about, so a status added later has to be placed on purpose. */
    @Test
    fun theDotFollowsTheResumableStatusesExactly() {
        val dotted = SyncStatus.entries.filter { mock(run(it)) == SyncBadge.ATTENTION }.toSet()
        assertEquals(
            setOf(SyncStatus.PAUSED, SyncStatus.STOPPED_CHALLENGE, SyncStatus.STOPPED_LOGIN, SyncStatus.STOPPED_RATE_LIMIT, SyncStatus.STOPPED_SHAPE),
            dotted,
        )
        assertEquals(SyncStatus.entries.filter { it.isResumable }.toSet(), dotted)
    }

    /** "Synced 5 min ago": the plain glyph; the time is on the Sync screen now. A cooldown left by a finished run is no dot. */
    @Test
    fun finishedRunsAreIdle() {
        assertEquals(SyncBadge.IDLE, mock(run(SyncStatus.DONE)))
        assertEquals(SyncBadge.IDLE, mock(run(SyncStatus.CANCELLED)))
        assertEquals(SyncBadge.IDLE, real(run(SyncStatus.DONE), SessionState.Valid("handle")))
    }

    /** "Not synced". */
    @Test
    fun nothingYet() {
        assertEquals(SyncBadge.IDLE, mock(null))
        assertEquals(SyncBadge.IDLE, real(null, SessionState.Valid("handle")))
    }

    /** The real library can't sync until the owner logs in: logged out, expired and challenged are dots, with or without a run. */
    @Test
    fun aSessionThatBlocksTheRealLibraryNeedsAttention() {
        for (session in listOf(SessionState.LoggedOut, SessionState.Expired(null), SessionState.Challenge(null, null))) {
            assertEquals(SyncBadge.ATTENTION, real(null, session), "$session, nothing synced")
            assertEquals(SyncBadge.ATTENTION, real(run(SyncStatus.DONE), session), "$session after a finished run")
        }
    }

    /** A session not read yet is no guess; and Mock mode's fake library needs no session at all. */
    @Test
    fun noDotWithoutAKnownSessionThatMatters() {
        assertEquals(SyncBadge.IDLE, real(run(SyncStatus.DONE), null))
        for (session in listOf(SessionState.LoggedOut, SessionState.Expired(null), SessionState.Challenge(null, null))) {
            assertEquals(SyncBadge.IDLE, SyncBadge.from(run(SyncStatus.DONE), session, requiresSession = false), "$session in Mock mode")
        }
    }

    @Test
    fun eachBadgeSaysItsState() {
        assertEquals("Sync", SyncBadge.IDLE.label)
        assertEquals("Sync, running", SyncBadge.RUNNING.label)
        assertEquals("Sync, needs attention", SyncBadge.ATTENTION.label)
    }
}
