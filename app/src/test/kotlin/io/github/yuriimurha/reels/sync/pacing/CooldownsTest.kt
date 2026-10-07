package io.github.yuriimurha.reels.sync.pacing

import kotlin.test.Test
import kotlin.test.assertEquals

class CooldownsTest {
    @Test
    fun firstRateLimitCoolsDownForAnHour() =
        assertEquals(1_000 + Cooldowns.SHORT_MS, Cooldowns.next(previousRateLimitAt = null, now = 1_000))

    @Test
    fun secondWithin24HoursCoolsDownForADay() =
        assertEquals(5_000 + Cooldowns.LONG_MS, Cooldowns.next(previousRateLimitAt = 1_000, now = 5_000))

    @Test
    fun secondAfter24HoursIsShortAgain() {
        val now = 1_000 + Cooldowns.WINDOW_MS
        assertEquals(now + Cooldowns.SHORT_MS, Cooldowns.next(previousRateLimitAt = 1_000, now = now))
    }
}
