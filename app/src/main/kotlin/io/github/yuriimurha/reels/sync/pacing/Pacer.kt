package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private var syncRequestsUntilBreak = sampleBreakInterval()

    fun newRun(): RunBudget = RunBudget()

    /** A sync request: waits its gap (and any break), yields to waiting interactive requests. */
    suspend fun <T> sync(run: RunBudget, request: suspend () -> T): T {
        while (true) {
            ensureAllowed()
            if (run.used >= policy.perRunBudget) throw PacerRefusal.RunBudgetReached()
            gate.lock()
            if (interactiveWaiting.get() > 0) {
                gate.unlock()
                while (interactiveWaiting.get() > 0) delay(YIELD_MS)
                continue
            }
            try {
                waitSinceLastRequest(policy.sampleGap(random))
                takeBreakIfDue()
                ensureAllowed()
                run.used++
                return execute(request)
            } finally {
                gate.unlock()
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

    private suspend fun ensureAllowed() {
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

    private suspend fun takeBreakIfDue() {
        if (policy.breakEvery == null) return
        syncRequestsUntilBreak -= 1
        if (syncRequestsUntilBreak > 0) return
        delay(random.nextLong(policy.breakMs.first, policy.breakMs.last + 1))
        syncRequestsUntilBreak = sampleBreakInterval()
    }

    private fun sampleBreakInterval(): Int =
        policy.breakEvery?.let { random.nextInt(it.first, it.last + 1) } ?: Int.MAX_VALUE

    private suspend fun <T> execute(request: suspend () -> T): T {
        requestLog.record(now())
        try {
            return request()
        } catch (e: InstagramException.RateLimited) {
            cooldowns.onRateLimited(now())
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
