package io.github.yuriimurha.reels.sync.pacing

/** Persists the rate-limit cooldown (spec 7.4). */
interface CooldownStore {
    /** Epoch millis until which no Instagram request may be made, or null. */
    suspend fun activeUntil(): Long?

    /** Records a rate limit at [now] and returns the new cooldown end. */
    suspend fun onRateLimited(now: Long): Long
}

object Cooldowns {
    const val SHORT_MS = 3_600_000L
    const val LONG_MS = 86_400_000L
    const val WINDOW_MS = 86_400_000L

    /** 1 h, or 24 h when the previous rate limit was less than 24 h ago. */
    fun next(previousRateLimitAt: Long?, now: Long): Long {
        val repeated = previousRateLimitAt != null && now - previousRateLimitAt < WINDOW_MS
        return now + if (repeated) LONG_MS else SHORT_MS
    }
}

/** Thread-safe: a sync worker records a rate limit while the Sync screen reads the cooldown. */
class InMemoryCooldownStore : CooldownStore {
    private val lock = Any()
    private var until: Long? = null
    private var lastRateLimitAt: Long? = null

    override suspend fun activeUntil(): Long? = synchronized(lock) { until }

    override suspend fun onRateLimited(now: Long): Long = synchronized(lock) {
        val next = Cooldowns.next(lastRateLimitAt, now)
        until = next
        lastRateLimitAt = now
        next
    }
}
