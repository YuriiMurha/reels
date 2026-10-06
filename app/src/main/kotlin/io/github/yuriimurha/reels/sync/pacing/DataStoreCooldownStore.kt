package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.data.settings.SettingsStore

/** Cooldown state in DataStore, so killing the app doesn't reset it (spec 7.4). */
class DataStoreCooldownStore(private val settings: SettingsStore) : CooldownStore {
    override suspend fun activeUntil(): Long? = settings.cooldown().until

    override suspend fun onRateLimited(now: Long): Long {
        val until = Cooldowns.next(settings.cooldown().lastRateLimitAt, now)
        settings.setCooldown(until = until, rateLimitAt = now)
        return until
    }
}
