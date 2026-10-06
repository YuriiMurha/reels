package io.github.yuriimurha.reels.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class CooldownState(val until: Long?, val lastRateLimitAt: Long?)

/** Small app settings and state that must survive process death (spec 5.4). One instance per process. */
class SettingsStore(private val store: DataStore<Preferences>) {
    val muted: Flow<Boolean> = store.data.map { it[MUTED] ?: false }

    suspend fun setMuted(muted: Boolean) {
        store.edit { it[MUTED] = muted }
    }

    suspend fun cooldown(): CooldownState =
        store.data.first().let { CooldownState(until = it[COOLDOWN_UNTIL], lastRateLimitAt = it[LAST_RATE_LIMIT_AT]) }

    suspend fun setCooldown(until: Long, rateLimitAt: Long) {
        store.edit {
            it[COOLDOWN_UNTIL] = until
            it[LAST_RATE_LIMIT_AT] = rateLimitAt
        }
    }

    companion object {
        private val MUTED = booleanPreferencesKey("muted")
        private val COOLDOWN_UNTIL = longPreferencesKey("cooldown_until")
        private val LAST_RATE_LIMIT_AT = longPreferencesKey("last_rate_limit_at")

        fun create(context: Context): SettingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile("settings") },
            ),
        )
    }
}
