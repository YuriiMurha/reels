package io.github.yuriimurha.reels.sync.pacing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Pacing values (spec 7.3). Only two instances exist; raising any value needs an explicit commit. */
class PacingPolicy private constructor(
    val minGapMs: Long,
    val medianGapMs: Long,
    val maxGapMs: Long,
    /** A break comes after a random number of sync requests in this range; null means never. */
    val breakEvery: IntRange?,
    val breakMs: LongRange,
    val perRunBudget: Int,
    val dailyBudget: Int,
    val interactiveMinGapMs: Long,
    val cdnConcurrency: Int,
    val cdnJitterMs: LongRange,
) {
    /**
     * Next gap between two sync requests: log-normal around the median, redrawn until it falls in
     * [minGapMs, maxGapMs].
     *
     * Truncated, not clamped: clamping put about 18 % of the gaps at exactly 4 s (and 6 % at exactly 12 s), a timing
     * signature no human has. Truncation also lifts the median from about 6.0 s to about 6.4 s, which is slower,
     * not faster: it never raises the request rate.
     */
    fun sampleGap(random: Random): Long {
        repeat(MAX_DRAWS) {
            val gap = (medianGapMs * exp(GAP_SIGMA * random.nextGaussian())).toLong()
            if (gap in minGapMs..maxGapMs) return gap
        }
        return random.nextLong(minGapMs, maxGapMs + 1) // vanishingly rare; still inside the bounds
    }

    companion object {
        private const val GAP_SIGMA = 0.45
        private const val MAX_DRAWS = 32

        /** The only policy allowed for real Instagram traffic. */
        val Conservative = PacingPolicy(
            minGapMs = 4_000, medianGapMs = 6_000, maxGapMs = 12_000,
            breakEvery = 15..30, breakMs = 60_000L..180_000L,
            perRunBudget = 300, dailyBudget = 600,
            interactiveMinGapMs = 2_000,
            cdnConcurrency = 2, cdnJitterMs = 200L..800L,
        )

        /** Fake backend only. In src/main it is referenced from di/Backend.kt and nowhere else (FastPolicyGuardTest). */
        val Fast = PacingPolicy(
            minGapMs = 20, medianGapMs = 35, maxGapMs = 60,
            breakEvery = null, breakMs = 0L..0L,
            perRunBudget = 300, dailyBudget = 600,
            interactiveMinGapMs = 2_000,
            cdnConcurrency = 4, cdnJitterMs = 0L..0L,
        )
    }
}

/** Standard normal sample (Box–Muller). */
internal fun Random.nextGaussian(): Double {
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
}
