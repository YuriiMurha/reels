package io.github.yuriimurha.reels.di

import android.content.SharedPreferences
import kotlin.coroutines.cancellation.CancellationException

/**
 * Which library the process runs on (P1). Release builds never use the fake library. Debug builds default to it, so a
 * fresh install, and every emulator, never needs a login (and never contacts Instagram).
 */
class BackendChoice(private val prefs: SharedPreferences, private val debugBuild: Boolean) {
    val useFake: Boolean get() = debugBuild && prefs.getBoolean(KEY_USE_FAKE, true)

    /**
     * `commit()`, not `apply()`: the process is restarted right after this, and the new one must read the new value.
     * Returns whether it was written.
     */
    fun setUseFake(value: Boolean): Boolean = prefs.edit().putBoolean(KEY_USE_FAKE, value).commit()

    companion object {
        const val PREFS = "backend"
        const val KEY_USE_FAKE = "use_fake"

        /** Set once the last 24 h of the real request log were copied out of the old `reels.db` (R68). */
        const val KEY_REQUEST_LOG_COPIED = "request_log_copied"
    }
}

/**
 * The Developer section's Mock mode switch. [usesFake] is the mode this process runs in (read once, at start), which is
 * not necessarily what is stored: the stored choice only takes effect on the next start. [cancelSync] cancels the unique
 * sync work and returns once WorkManager has recorded that; [restart] is [ProcessRestart.restart] in the app. Both are
 * injected so tests can observe them.
 */
class MockModeSwitch(
    val usesFake: Boolean,
    private val choice: BackendChoice,
    private val cancelSync: suspend () -> Unit,
    private val restart: () -> Unit,
) {
    /**
     * Cancels the queued sync, THEN stores the new mode, THEN restarts. Work that WorkManager still holds names a run id
     * only, and both libraries number their runs from 1, so a restarted process would replay it on the other library.
     * Returns whether it restarted: nothing is changed when the work can't be cancelled, and there is no restart when the
     * new mode could not be written (the new process would read the old one).
     */
    suspend fun change(useFake: Boolean): Boolean {
        try {
            cancelSync()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        if (!choice.setUseFake(useFake)) return false
        restart()
        return true
    }
}
