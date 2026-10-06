package io.github.yuriimurha.reels.sync.pacing

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
