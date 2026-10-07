package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        random: Random = Random(1),
    ) = Pacer(PacingPolicy.Conservative, log, cooldowns, random, now = { testScheduler.currentTime })

    /** Same stream as Random(1), but every break interval is [interval] requests, so the schedule is known. */
    private fun fixedBreakInterval(interval: Int): Random = object : Random() {
        private val delegate = Random(1)
        override fun nextBits(bitCount: Int): Int = delegate.nextBits(bitCount)
        override fun nextInt(from: Int, until: Int): Int = interval
    }

    /** Hangs the named coroutine's budget check, which for an interactive request runs while it holds the gate. */
    private class StallableRequestLog(private val delegate: RequestLog = InMemoryRequestLog()) : RequestLog by delegate {
        @Volatile
        var stallFor: String? = null

        override suspend fun countSince(since: Long): Int {
            if (stallFor != null && currentCoroutineContext()[CoroutineName]?.name == stallFor) awaitCancellation()
            return delegate.countSince(since)
        }
    }

    private class ThrowingCooldowns : CooldownStore {
        override suspend fun activeUntil(): Long? = null
        override suspend fun onRateLimited(now: Long): Long = throw IllegalStateException("cooldown store is down")
    }

    /** A store whose write takes [writeMs] of (virtual) time, to cancel the caller in the middle of it. */
    private class SlowCooldowns(private val writeMs: Long) : CooldownStore {
        var recorded = false
        override suspend fun activeUntil(): Long? = null
        override suspend fun onRateLimited(now: Long): Long {
            delay(writeMs)
            recorded = true
            return now + Cooldowns.SHORT_MS
        }
    }

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

    /**
     * R79: the interactive lane can wait seconds for the gate. A caller whose condition (a valid session) may have changed
     * meanwhile checks it from inside the gate: if it fails, nothing is sent, nothing is logged, and the gate is let go.
     */
    @Test
    fun aThrowingPreconditionSendsAndLogsNothingAndReleasesTheGate() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        var sent = 0

        assertFailsWith<IllegalStateException> {
            pacer.interactive(precondition = { throw IllegalStateException("not ready") }) { sent++ }
        }
        assertEquals(0, sent, "the request was never run")
        assertEquals(0, log.countSince(-1), "and never logged: it did not count against the budget")

        // The gate is free again: a second request goes through (a held gate would never answer, and the timeout says so).
        withTimeout(60_000) { pacer.interactive { sent++ } }
        assertEquals(1, sent)
        assertEquals(1, log.countSince(-1))
    }

    /** The precondition is the LAST check before the request: after the gap has been waited out, before the request is logged. */
    @Test
    fun thePreconditionRunsAfterTheGapAndBeforeTheRequestIsLogged() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        pacer.sync(pacer.newRun()) {}
        val previousEnd = testScheduler.currentTime
        val loggedBefore = log.countSince(-1)
        var checkedAt = -1L
        var loggedWhenChecked = -1

        pacer.interactive(precondition = {
            checkedAt = testScheduler.currentTime
            loggedWhenChecked = log.countSince(-1)
        }) {}

        assertEquals(previousEnd + PacingPolicy.Conservative.interactiveMinGapMs, checkedAt, "checked after the 2 s gap, not before it")
        assertEquals(loggedBefore, loggedWhenChecked, "checked before this request was recorded")
        assertEquals(loggedBefore + 1, log.countSince(-1))
    }

    @Test
    fun aRefusalComesBeforeThePrecondition() = runTest {
        val cooldowns = InMemoryCooldownStore()
        cooldowns.onRateLimited(testScheduler.currentTime)
        val pacer = pacer(cooldowns = cooldowns)
        var checked = false

        assertFailsWith<PacerRefusal.CoolingDown> { pacer.interactive(precondition = { checked = true }) {} }

        assertTrue(!checked, "a cooling-down Pacer refuses before it asks anything else")
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

    @Test
    fun interactiveRequestsNeverWaitBehindSyncGapsOrBreaks() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        val syncStarts = mutableListOf<Long>()
        val probes = mutableListOf<Pair<Long, Long>>() // requested at, started at
        val probeJobs = mutableListOf<Job>()
        repeat(40) {
            pacer.sync(run) {
                syncStarts += testScheduler.currentTime
                // One second after each sync request an interactive one arrives, while the next sync waits its gap.
                probeJobs += launch {
                    delay(1_000)
                    val requestedAt = testScheduler.currentTime
                    pacer.interactive { probes += requestedAt to testScheduler.currentTime }
                }
            }
        }
        probeJobs.joinAll()

        assertTrue(syncStarts.zipWithNext { a, b -> b - a }.any { it > 59_000 }, "no break in this run: $syncStarts")
        assertEquals(40, probes.size)
        val waits = probes.map { (requested, started) -> started - requested }
        // The previous request ended 1 s before the probe, so the 2 s interactive gap leaves exactly 1 s to wait.
        assertTrue(waits.all { it <= 1_000 }, "interactive waits $waits")
    }

    @Test
    fun interactiveRequestOvertakesAQueuedSyncRequest() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        val order = mutableListOf<String>()
        val a = launch { pacer.sync(run) { order += "A"; delay(1_000) } }
        val b = launch { pacer.sync(run) { order += "B" } }
        advanceTimeBy(500) // A is in flight, B is queued behind it
        val i = launch { pacer.interactive { order += "I" } }
        joinAll(a, b, i)
        assertEquals(listOf("A", "I", "B"), order)
    }

    @Test
    fun interactiveRequestQueuedBehindAReadySyncRequestStillGoesFirst() = runTest {
        val log = StallableRequestLog()
        val pacer = pacer(log = log)
        val run = pacer.newRun()
        val order = mutableListOf<String>()
        pacer.sync(run) {} // t=0
        val b = launch(CoroutineName("B")) { pacer.sync(run) { order += "B" } }
        runCurrent() // B sampled its gap (4-12 s) and waits for it without the gate
        val j = launch(CoroutineName("J")) { pacer.interactive { order += "J" } }
        runCurrent() // J holds the gate through its 2 s minimum gap
        log.stallFor = "J" // from now on J hangs, still holding the gate, before it can send anything
        advanceTimeBy(12_500) // B's gap is over; it queues for the gate behind J
        val i = launch(CoroutineName("I")) { pacer.interactive { order += "I" } }
        runCurrent() // I queues behind B
        j.cancel() // the gate is released with no request having run, B is first in line and ready to send
        joinAll(b, i)
        assertEquals(listOf("I", "B"), order)
    }

    @Test
    fun interactiveRequestDuringASyncBreakStartsWithinItsOwnMinimumGap() = runTest {
        val pacer = pacer(random = fixedBreakInterval(15))
        val run = pacer.newRun()
        repeat(14) { pacer.sync(run) {} } // the 15th sync request is preceded by a break
        val previousEnd = testScheduler.currentTime
        var syncStartedAt = -1L
        val syncJob = launch { pacer.sync(run) { syncStartedAt = testScheduler.currentTime } }
        advanceTimeBy(500)
        var interactiveStartedAt = -1L
        pacer.interactive { interactiveStartedAt = testScheduler.currentTime }
        syncJob.join()

        assertTrue(syncStartedAt - previousEnd > 59_000, "expected a break before the sync request, got ${syncStartedAt - previousEnd}")
        assertEquals(previousEnd + 2_000, interactiveStartedAt)
    }

    @Test
    fun syncNeverStartsSoonerThanTheMinimumGapAfterAnInteractiveRequest() = runTest {
        val pacer = pacer()
        val run = pacer.newRun()
        pacer.sync(run) {}
        var syncStartedAt = -1L
        val syncJob = launch { pacer.sync(run) { syncStartedAt = testScheduler.currentTime } }
        runCurrent() // the sync request has sampled its gap (4-12 s) and is waiting
        var interactiveEndedAt = -1L
        val interactiveJob = launch {
            // Still running when the sync request's own gap has long passed.
            pacer.interactive { delay(20_000) }
            interactiveEndedAt = testScheduler.currentTime
        }
        joinAll(syncJob, interactiveJob)
        assertTrue(
            syncStartedAt >= interactiveEndedAt + PacingPolicy.Conservative.minGapMs,
            "sync started at $syncStartedAt, interactive ended at $interactiveEndedAt",
        )
    }

    /**
     * Runs a sync request whose gap is already planned while an interactive request holds the gate for 20 s, and
     * returns how long the sync request then waited after the interactive request ended.
     */
    private suspend fun TestScope.waitAfterInteractive(seed: Int): Long {
        val pacer = pacer(random = Random(seed))
        val run = pacer.newRun()
        pacer.sync(run) {} // the first request: nothing to wait for
        var syncStartedAt = -1L
        val syncJob = launch { pacer.sync(run) { syncStartedAt = testScheduler.currentTime } }
        runCurrent() // the second sync request has planned its gap (4-12 s) and waits for it without the gate
        // The interactive request is still running when that planned gap has long passed.
        pacer.interactive { delay(20_000) }
        val interactiveEndedAt = testScheduler.currentTime
        syncJob.join()
        return syncStartedAt - interactiveEndedAt
    }

    @Test
    fun syncAfterAnInteractiveRequestWaitsAFreshGap() = runTest {
        val seed = 1
        // Replay the Pacer's draws on a second stream with the same seed: the break interval (construction),
        // the first sync request's gap, the second one's planned gap, then the fresh gap after the interleave.
        val replay = Random(seed)
        replay.nextInt(15, 31)
        repeat(2) { PacingPolicy.Conservative.sampleGap(replay) }
        val freshGap = PacingPolicy.Conservative.sampleGap(replay)

        val wait = waitAfterInteractive(seed)

        assertTrue(wait >= PacingPolicy.Conservative.minGapMs, "waited $wait")
        assertTrue(freshGap > PacingPolicy.Conservative.minGapMs, "seed $seed must draw a gap above the minimum, got $freshGap")
        assertEquals(freshGap, wait, "the wait after an interleaved request is the next draw from the stream")
    }

    @Test
    fun theWaitAfterAnInteractiveRequestIsNotAlwaysTheMinimumGap() = runTest {
        val waits = (1..50).map { waitAfterInteractive(it) }
        assertTrue(waits.all { it in 4_000L..12_000L }, "waits $waits")
        assertTrue(waits.toSet().size > 25, "waits barely vary: $waits")
        assertTrue(waits.count { it == PacingPolicy.Conservative.minGapMs } < 5, "waits $waits")
    }

    @Test
    fun firstRequestOfAProcessWaitsForTheLastLoggedOne() = runTest {
        delay(100_000)
        val latest = testScheduler.currentTime - 1_000
        val pacer = pacer(log = InMemoryRequestLog(listOf(latest)))
        var startedAt = -1L
        pacer.sync(pacer.newRun()) { startedAt = testScheduler.currentTime }
        assertTrue(startedAt >= latest + PacingPolicy.Conservative.minGapMs, "started at $startedAt, last logged at $latest")
        assertTrue(startedAt <= latest + PacingPolicy.Conservative.maxGapMs, "started at $startedAt, last logged at $latest")
    }

    @Test
    fun firstRequestWithAnEmptyLogIsStillImmediate() = runTest {
        delay(100_000)
        val pacer = pacer()
        var startedAt = -1L
        pacer.sync(pacer.newRun()) { startedAt = testScheduler.currentTime }
        assertEquals(100_000, startedAt)
    }

    @Test
    fun firstRequestLongAfterTheLastLoggedOneIsImmediate() = runTest {
        delay(100_000)
        val pacer = pacer(log = InMemoryRequestLog(listOf(testScheduler.currentTime - 60_000)))
        var startedAt = -1L
        pacer.sync(pacer.newRun()) { startedAt = testScheduler.currentTime }
        assertEquals(100_000, startedAt)
    }

    /** Counts how often the Pacer asks the log for its latest entry. */
    private class CountingLatestLog(private val delegate: RequestLog) : RequestLog by delegate {
        var latestReads = 0

        override suspend fun latest(): Long? {
            latestReads++
            return delegate.latest()
        }
    }

    @Test
    fun theLogIsReadForItsLatestEntryOnlyOnce() = runTest {
        delay(100_000)
        val log = CountingLatestLog(InMemoryRequestLog(listOf(testScheduler.currentTime - 60_000)))
        val pacer = pacer(log = log)
        val run = pacer.newRun()
        repeat(3) { pacer.sync(run) {} }
        pacer.interactive {}
        repeat(2) { pacer.sync(run) {} }
        assertEquals(1, log.latestReads)
    }

    /** An empty log whose latest-entry read takes (virtual) time, so a gate holder is mid-read while others queue. */
    private class SlowEmptyLog(private val delegate: RequestLog = InMemoryRequestLog()) : RequestLog by delegate {
        var latestReads = 0

        override suspend fun latest(): Long? {
            latestReads++
            delay(10)
            return null
        }
    }

    @Test
    fun anEmptyLogIsReadOnlyOnceEvenIfTheFirstGateHolderYieldsToAnInteractiveRequest() = runTest {
        val log = SlowEmptyLog()
        val pacer = pacer(log = log)
        val syncJob = launch { pacer.sync(pacer.newRun()) {} }
        runCurrent() // the sync request holds the gate and is reading the (empty) log
        val interactiveJob = launch { pacer.interactive {} }
        runCurrent() // the interactive request is queued for the gate; the sync request will yield to it
        joinAll(syncJob, interactiveJob)
        assertEquals(1, log.latestReads)
    }

    @Test
    fun firstInteractiveRequestOfAProcessKeepsItsGapFromTheLastLoggedOne() = runTest {
        delay(100_000)
        val latest = testScheduler.currentTime - 500
        val pacer = pacer(log = InMemoryRequestLog(listOf(latest)))
        var startedAt = -1L
        pacer.interactive { startedAt = testScheduler.currentTime }
        assertEquals(latest + PacingPolicy.Conservative.interactiveMinGapMs, startedAt)
    }

    // A log row dated in the future (clock set back, emulator snapshot restore) must count as "now", not stall the gate.

    @Test
    fun aFutureDatedLogEntryDoesNotStallTheFirstSyncRequest() = runTest {
        delay(100_000)
        val now = testScheduler.currentTime
        val pacer = pacer(log = InMemoryRequestLog(listOf(now + 3 * Pacer.DAY_MS)))
        var startedAt = -1L
        pacer.sync(pacer.newRun()) { startedAt = testScheduler.currentTime }
        assertTrue(
            startedAt in now + PacingPolicy.Conservative.minGapMs..now + PacingPolicy.Conservative.maxGapMs,
            "started ${startedAt - now} ms after now, with the last logged request 3 days ahead",
        )
    }

    @Test
    fun aFutureDatedLogEntryDoesNotStallTheFirstInteractiveRequest() = runTest {
        delay(100_000)
        val now = testScheduler.currentTime
        val pacer = pacer(log = InMemoryRequestLog(listOf(now + 3 * Pacer.DAY_MS)))
        var startedAt = -1L
        pacer.interactive { startedAt = testScheduler.currentTime }
        assertEquals(now + PacingPolicy.Conservative.interactiveMinGapMs, startedAt)
    }

    @Test
    fun twoCallersSharingARunBudgetNeverExceedIt() = runTest {
        val log = InMemoryRequestLog()
        val pacer = pacer(log = log)
        val run = pacer.newRun()
        repeat(299) { pacer.sync(run) {} }
        val results = List(2) { async { runCatching { pacer.sync(run) {} } } }.awaitAll()

        assertEquals(1, results.count { it.isSuccess }, "results $results")
        assertTrue(results.single { it.isFailure }.exceptionOrNull() is PacerRefusal.RunBudgetReached)
        assertEquals(300, run.used)
        assertEquals(300, log.countSince(-1))
    }

    @Test
    fun concurrentSyncCallersStillTakeTheBreakThatBecomesDue() = runTest {
        val pacer = pacer(random = fixedBreakInterval(15))
        val run = pacer.newRun()
        repeat(13) { pacer.sync(run) {} } // two requests left before the break
        val starts = mutableListOf<Long>()
        val callers = List(2) { launch { pacer.sync(run) { starts += testScheduler.currentTime } } }
        callers.joinAll()
        assertEquals(2, starts.size)
        assertTrue(starts[1] - starts[0] > 59_000, "second concurrent caller skipped the break: $starts")
    }

    @Test
    fun rateLimitIsStillReportedWhenRecordingTheCooldownFails() = runTest {
        val pacer = pacer(cooldowns = ThrowingCooldowns())
        val failure = assertFailsWith<InstagramException.RateLimited> {
            pacer.sync(pacer.newRun()) { throw InstagramException.RateLimited() }
        }
        assertTrue(failure.suppressed.any { it is IllegalStateException }, "suppressed ${failure.suppressed.toList()}")
    }

    @Test
    fun cooldownIsRecordedEvenIfTheCallerIsCancelledMidWrite() = runTest {
        val cooldowns = SlowCooldowns(writeMs = 100)
        val pacer = pacer(cooldowns = cooldowns)
        val caller = launch {
            // The rate limit still reaches a cancelled caller; swallow it so it does not fail the test scope.
            runCatching { pacer.sync(pacer.newRun()) { throw InstagramException.RateLimited() } }
        }
        runCurrent() // the request failed; the cooldown write is in progress
        caller.cancel()
        advanceUntilIdle()
        assertTrue(cooldowns.recorded, "cooldown write was abandoned")
    }
}
