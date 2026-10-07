package io.github.yuriimurha.reels.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.yuriimurha.reels.sync.pacing.Cooldowns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

data class CooldownState(val until: Long?, val lastRateLimitAt: Long?)

/** What `toString()` prints in place of anything that must not reach a log or a crash report (two full blocks). */
internal const val REDACTED = "\u2588\u2588"

/**
 * The persisted session state, encoded by the session package. The session itself lives only in CookieManager.
 * [challengeUrl] is where Instagram wants verification: `toString()` never prints it.
 */
data class StoredSession(val kind: String?, val handle: String?, val challengeUrl: String?) {
    override fun toString(): String =
        "StoredSession(kind=$kind, handle=$handle, challengeUrl=${challengeUrl?.let { REDACTED }})"
}

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

    val session: Flow<StoredSession> = store.data.map {
        StoredSession(kind = it[SESSION_KIND], handle = it[SESSION_HANDLE], challengeUrl = it[SESSION_CHALLENGE_URL])
    }

    suspend fun setSession(session: StoredSession) {
        store.edit {
            it.setOrRemove(SESSION_KIND, session.kind)
            it.setOrRemove(SESSION_HANDLE, session.handle)
            it.setOrRemove(SESSION_CHALLENGE_URL, session.challengeUrl)
        }
    }

    /**
     * R84: the pk of the Instagram account a library belongs to, one key per library (`library_account_pk_<library>`), since
     * the fake and the real library each have their own. [library] is `"fake"` or `"real"` (`SyncWorker.kindOf`).
     */
    suspend fun libraryAccountPk(library: String): String? = store.data.first()[libraryAccountKey(library)]

    /** Null removes it (Delete library). */
    suspend fun setLibraryAccountPk(library: String, pk: String?) {
        store.edit { it.setOrRemove(libraryAccountKey(library), pk) }
    }

    private fun libraryAccountKey(library: String): Preferences.Key<String> {
        require(library == "fake" || library == "real") { "library is \"fake\" or \"real\"" }
        return stringPreferencesKey("library_account_pk_$library")
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        private val MUTED = booleanPreferencesKey("muted")
        private val COOLDOWN_UNTIL = longPreferencesKey("cooldown_until")
        private val LAST_RATE_LIMIT_AT = longPreferencesKey("last_rate_limit_at")
        private val SESSION_KIND = stringPreferencesKey("session_kind")
        private val SESSION_HANDLE = stringPreferencesKey("session_handle")
        private val SESSION_CHALLENGE_URL = stringPreferencesKey("session_challenge_url")

        fun create(context: Context): SettingsStore = open(produceFile = { context.preferencesDataStoreFile("settings") })

        /** [scope] and [now] are only overridden by tests. */
        internal fun open(
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            now: () -> Long = System::currentTimeMillis,
            produceFile: () -> File,
        ): SettingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { corruptionFallback(now()) },
                scope = scope,
                produceFile = produceFile,
            ),
        )

        /**
         * What replaces a settings file that cannot be read (ruling R54). Wiping it would silently end an active
         * cooldown, even a 24 h one, so the replacement assumes the worst case: a rate limit just happened.
         * `cooldown_until` is [Cooldowns.SHORT_MS] (spec 7.3's 1 h) from [now], and `last_rate_limit_at` is [now], so a
         * rate limit within the next 24 h escalates straight to the 24 h tier. The session keys stay empty: no kind
         * reads as LoggedOut, and validation sorts that out once the cooldown ends.
         */
        internal fun corruptionFallback(now: Long): Preferences = preferencesOf(
            COOLDOWN_UNTIL to now + Cooldowns.SHORT_MS,
            LAST_RATE_LIMIT_AT to now,
        )
    }
}
