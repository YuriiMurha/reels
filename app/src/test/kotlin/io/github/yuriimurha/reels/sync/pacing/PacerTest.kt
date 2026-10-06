package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacerTest {
    private fun TestScope.pacer(
        log: RequestLog = InMemoryRequestLog(),
        cooldowns: CooldownStore = InMemoryCooldownStore(),
    ) = Pacer(PacingPolicy.Conservative, log, cooldowns, Random(1), now = { testScheduler.currentTime })

    @Test
    fun gapsStayInBoundsWithMedianNearSixSecondsAndRegularBreaks() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        val starts = mutableListOf<Long>()
        repeat(200) { pacer.sync(run) { starts += testScheduler.currentTime } }

        val gaps = starts.zipWithNext { a, b -> b - a }
        val (breaks, normal) = gaps.partition { it > 59_000 }
        assertTrue(normal.all { it in 4_000..12_000 }, "gaps ${normal.minOrNull()}..${normal.maxOrNull()}")
        assertTrue(breaks.all { it in 64_000..192_000 }, "breaks $breaks")
        assertTrue(breaks.size in 6..14, "break count ${breaks.size}")
        val median = normal.sorted()[normal.size / 2]
        assertTrue(median in 5_000..7_000, "median $median")
    }

    @Test
    fun runBudgetStopsAt300Requests() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        val run = pacer.newRun()
        repeat(300) { pacer.sync(run) {} }
        assertFailsWith<PacerRefusal.RunBudgetReached> { pacer.sync(run) {} }
        assertEquals(300, run.used)
        assertEquals(300, log.countSince(-1))
    }

    @Test
    fun dailyBudgetSpansRunsAndFreesAfter24Hours() = runTest {
        val pacer = pacer(log = InMemoryRequestLog(List(600) { 0L }))
        val refusal = assertFailsWith<PacerRefusal.DailyBudgetReached> { pacer.sync(pacer.newRun()) {} }
        assertEquals(Pacer.DAY_MS, refusal.freesAt)
        advanceTimeBy(Pacer.DAY_MS + 1)
        pacer.sync(pacer.newRun()) {}
    }

    @Test
    fun interactiveRequestJumpsAheadOfQueuedSyncRequests() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        val order = mutableListOf<String>()
        val syncJob = launch { repeat(3) { i -> pacer.sync(run) { order += "sync$i"; delay(1_000) } } }
        advanceTimeBy(500)
        val interactiveJob = launch { pacer.interactive { order += "interactive" } }
        joinAll(syncJob, interactiveJob)
        assertEquals(listOf("sync0", "interactive", "sync1", "sync2"), order)
    }

    @Test
    fun interactiveKeepsTwoSecondsFromThePreviousRequest() = runTest {
        val pacer = pacer()
        pacer.sync(pacer.newRun()) {}
        val previousEnd = testScheduler.currentTime
        var startedAt = -1L
        pacer.interactive { startedAt = testScheduler.currentTime }
        assertEquals(previousEnd + 2_000, startedAt)
    }

    @Test
    fun rateLimitStartsACooldownThatRefusesBothLanes() = runTest {
        val pacer = pacer()
        assertFailsWith<InstagramException.RateLimited> {
            pacer.sync(pacer.newRun()) { throw InstagramException.RateLimited() }
        }
        val refusal = assertFailsWith<PacerRefusal.CoolingDown> { pacer.sync(pacer.newRun()) {} }
        assertEquals(Cooldowns.SHORT_MS, refusal.until)
        assertFailsWith<PacerRefusal.CoolingDown> { pacer.interactive {} }
    }

    @Test
    fun failedRequestsStillCountAgainstTheBudget() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        assertFailsWith<InstagramException.Transient> {
            pacer.sync(pacer.newRun()) { throw InstagramException.Transient() }
        }
        assertEquals(1, log.countSince(-1))
    }

    @Test
    fun cdnLaneAllowsTwoAtATimeAndIsNotBudgeted() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        var active = 0
        var peak = 0
        List(6) {
            launch {
                pacer.cdn {
                    active++
                    peak = maxOf(peak, active)
                    delay(1_000)
                    active--
                }
            }
        }.joinAll()
        assertEquals(2, peak)
        assertEquals(0, log.countSince(-1))
    }

    @Test
    fun statusReportsUsageAndCooldown() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        repeat(3) { pacer.sync(run) {} }
        val before = pacer.status()
        assertEquals(3, before.requestsLast24h)
        assertEquals(600, before.dailyBudget)
        assertEquals(300, before.perRunBudget)
        assertNull(before.cooldownUntil)
        assertFailsWith<InstagramException.RateLimited> { pacer.sync(run) { throw InstagramException.RateLimited() } }
        assertTrue(pacer.status().cooldownUntil!! > testScheduler.currentTime)
    }
}
