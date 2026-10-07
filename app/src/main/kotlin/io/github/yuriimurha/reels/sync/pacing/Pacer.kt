package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

data class PacerStatus(
    val requestsLast24h: Int,
    val dailyBudget: Int,
    val perRunBudget: Int,
    val cooldownUntil: Long?,
)

/**
 * The single gate for Instagram API requests (spec 7.3): one request at a time, randomised gaps,
 * breaks, per-run and rolling 24 h budgets, an interactive lane with priority, and a separate CDN lane.
 */
class Pacer(
    val policy: PacingPolicy,
    private val requestLog: RequestLog,
    private val cooldowns: CooldownStore,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Counts one sync run's requests against [PacingPolicy.perRunBudget]. */
    class RunBudget internal constructor() {
        var used: Int = 0
            internal set
    }

    private val gate = Mutex()
    private val interactiveWaiting = AtomicInteger(0)
    private val cdnLane = Semaphore(policy.cdnConcurrency)
    private var lastRequestEndedAt: Long? = null

    /** True once [lastRequestEndedAt] was seeded from the persisted request log (see [sync] and [interactive]). */
    private var seeded = false
    private var syncRequestsUntilBreak = sampleBreakInterval()

    fun newRun(): RunBudget = RunBudget()

    /**
     * A sync request. It samples its gap (and a break, if one is due) once, then waits for that moment WITHOUT
     * holding the gate, so interactive requests are never queued behind a sync gap or break. Whenever it takes
     * the gate it first yields to waiting interactive requests, and after any other request ran in the meantime
     * it waits a fresh gap from that request's end (a new draw, so never less than [PacingPolicy.minGapMs]).
     * The very first request of a process waits out the gap after the last request the log remembers.
     */
    suspend fun <T> sync(run: RunBudget, request: suspend () -> T): T {
        var planned = false
        var plannedFrom: Long? = null // lastRequestEndedAt that notBefore was computed from
        var notBefore = 0L
        var includesBreak = false
        while (true) {
            ensureAllowed()
            ensureRunBudget(run)
            gate.lock()
            var holding = true
            try {
                // A restarted process does not know when the last request ended; the persisted log does.
                if (!seeded) { lastRequestEndedAt = lastRequestEndedAt ?: requestLog.latest(); seeded = true }
                if (interactiveWaiting.get() > 0) {
                    gate.unlock()
                    holding = false
                    while (interactiveWaiting.get() > 0) delay(YIELD_MS)
                    continue
                }
                val last = lastRequestEndedAt
                if (!planned) {
                    val gap = policy.sampleGap(random) // drawn even for the first request: keeps the random stream stable
                    includesBreak = breakIsDue()
                    notBefore = (last?.plus(gap) ?: now()) + if (includesBreak) sampleBreakMs() else 0L
                    plannedFrom = last
                    planned = true
                } else if (last != null && last != plannedFrom) {
                    // Another request (an interactive one) ran while this one waited. The gap after it is a fresh
                    // draw, so it is never exactly minGapMs (a constant gap is a timing signature) and never shorter.
                    notBefore = maxOf(notBefore, last + policy.sampleGap(random))
                    plannedFrom = last
                }
                if (!includesBreak && breakIsDue()) {
                    // Another sync request used up the slot while this one waited: the break is now owed here.
                    includesBreak = true
                    notBefore = maxOf(notBefore, now() + sampleBreakMs())
                }
                val wait = notBefore - now()
                if (wait > 0) {
                    gate.unlock()
                    holding = false
                    delay(wait)
                    continue
                }
                ensureAllowed()
                ensureRunBudget(run)
                consumeBreakSlot()
                run.used++
                return execute(request)
            } finally {
                if (holding) gate.unlock()
            }
        }
    }

    /** A request the owner is waiting for (viewer link refresh, session check): short gap, goes first. */
    suspend fun <T> interactive(request: suspend () -> T): T {
        ensureAllowed()
        interactiveWaiting.incrementAndGet()
        var holding = false
        try {
            gate.lock()
            holding = true
            interactiveWaiting.decrementAndGet()
            if (!seeded) { lastRequestEndedAt = lastRequestEndedAt ?: requestLog.latest(); seeded = true }
            waitSinceLastRequest(policy.interactiveMinGapMs)
            ensureAllowed()
            return execute(request)
        } finally {
            if (holding) gate.unlock() else interactiveWaiting.decrementAndGet()
        }
    }

    /** A CDN download (thumbnails, video). Limited concurrency with jitter; not an API request, not budgeted. */
    suspend fun <T> cdn(download: suspend () -> T): T = cdnLane.withPermit {
        val jitter = policy.cdnJitterMs
        if (jitter.last > 0) delay(random.nextLong(jitter.first, jitter.last + 1))
        download()
    }

    suspend fun status(): PacerStatus {
        val t = now()
        return PacerStatus(
            requestsLast24h = requestLog.countSince(t - DAY_MS),
            dailyBudget = policy.dailyBudget,
            perRunBudget = policy.perRunBudget,
            cooldownUntil = cooldowns.activeUntil()?.takeIf { it > t },
        )
    }

    /**
     * Throws the refusal an [interactive] or [sync] request would meet right now (cooldown, then the 24 h budget),
     * without waiting or recording anything. Lets a caller refuse before it changes state for a request.
     */
    suspend fun ensureAllowed() {
        val t = now()
        cooldowns.activeUntil()?.let { until -> if (until > t) throw PacerRefusal.CoolingDown(until) }
        val since = t - DAY_MS
        if (requestLog.countSince(since) >= policy.dailyBudget) {
            throw PacerRefusal.DailyBudgetReached((requestLog.oldestSince(since) ?: t) + DAY_MS)
        }
    }

    private suspend fun waitSinceLastRequest(gapMs: Long) {
        val last = lastRequestEndedAt ?: return
        val remaining = last + gapMs - now()
        if (remaining > 0) delay(remaining)
    }

    private fun ensureRunBudget(run: RunBudget) {
        if (run.used >= policy.perRunBudget) throw PacerRefusal.RunBudgetReached()
    }

    /** True when the next sync request is the one a break precedes. */
    private fun breakIsDue(): Boolean = policy.breakEvery != null && syncRequestsUntilBreak <= 1

    private fun sampleBreakMs(): Long = random.nextLong(policy.breakMs.first, policy.breakMs.last + 1)

    /** Counts a sync request that is about to be sent; a due break has been waited out by now. */
    private fun consumeBreakSlot() {
        if (policy.breakEvery == null) return
        syncRequestsUntilBreak -= 1
        if (syncRequestsUntilBreak <= 0) syncRequestsUntilBreak = sampleBreakInterval()
    }

    private fun sampleBreakInterval(): Int =
        policy.breakEvery?.let { random.nextInt(it.first, it.last + 1) } ?: Int.MAX_VALUE

    private suspend fun <T> execute(request: suspend () -> T): T {
        requestLog.record(now())
        try {
            return request()
        } catch (e: InstagramException.RateLimited) {
            // The cooldown must survive the caller being cancelled right now, and must never mask the rate limit.
            withContext(NonCancellable) {
                try {
                    cooldowns.onRateLimited(now())
                } catch (failure: Exception) {
                    e.addSuppressed(failure)
                }
            }
            throw e
        } finally {
            lastRequestEndedAt = now()
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        private const val YIELD_MS = 10L
    }
}
