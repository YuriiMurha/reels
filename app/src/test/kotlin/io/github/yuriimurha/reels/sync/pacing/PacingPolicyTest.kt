package io.github.yuriimurha.reels.sync.pacing

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacingPolicyTest {
    @Test
    fun conservativePolicyKeepsTheSpecValues() = with(PacingPolicy.Conservative) {
        assertEquals(4_000L, minGapMs)
        assertEquals(6_000L, medianGapMs)
        assertEquals(12_000L, maxGapMs)
        assertEquals(15..30, breakEvery)
        assertEquals(60_000L..180_000L, breakMs)
        assertEquals(300, perRunBudget)
        assertEquals(600, dailyBudget)
        assertEquals(2_000L, interactiveMinGapMs)
        assertEquals(2, cdnConcurrency)
        assertEquals(200L..800L, cdnJitterMs)
    }

    @Test
    fun fastPolicyKeepsTheSameInteractiveGapAsConservative() =
        assertEquals(PacingPolicy.Conservative.interactiveMinGapMs, PacingPolicy.Fast.interactiveMinGapMs)

    /** Clamping piled about 18 % of the draws on exactly 4,000 ms and 6 % on 12,000 ms: a timing signature. */
    @Test
    fun gapsAreTruncatedNotClamped() {
        val random = Random(1)
        val gaps = List(10_000) { PacingPolicy.Conservative.sampleGap(random) }.sorted()
        val atMin = gaps.count { it == 4_000L } / gaps.size.toDouble()
        val atMax = gaps.count { it == 12_000L } / gaps.size.toDouble()
        val median = gaps[gaps.size / 2]

        assertTrue(gaps.all { it in 4_000L..12_000L }, "gaps ${gaps.first()}..${gaps.last()}")
        assertTrue(atMin < 0.01, "fraction at exactly 4,000 ms: $atMin")
        assertTrue(atMax < 0.01, "fraction at exactly 12,000 ms: $atMax")
        assertTrue(median in 5_500L..7_000L, "median $median")
    }

    @Test
    fun aGapStillComesBackInsideTheBoundsWhenEveryDrawIsOutOfRange() {
        // All-zero bits make every Box-Muller draw about +7.4 sigma: a gap far above the maximum, 32 times running.
        val stuck = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
        val gap = PacingPolicy.Conservative.sampleGap(stuck)
        assertTrue(gap in 4_000L..12_000L, "gap $gap")
    }

    @Test
    fun fastPolicyGapsAlsoStayInsideTheirBounds() {
        val random = Random(1)
        val gaps = List(10_000) { PacingPolicy.Fast.sampleGap(random) }
        assertTrue(gaps.all { it in 20L..60L }, "gaps ${gaps.minOrNull()}..${gaps.maxOrNull()}")
    }
}
