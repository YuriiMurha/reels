package io.github.yuriimurha.reels.di

import android.content.SharedPreferences

/**
 * Which library the process runs on (P1). Release builds never use the fake library. Debug builds default to it, so a
 * fresh install, and every emulator, never needs a login (and never contacts Instagram).
 */
class BackendChoice(private val prefs: SharedPreferences, private val debugBuild: Boolean) {
    val useFake: Boolean get() = debugBuild && prefs.getBoolean(KEY_USE_FAKE, true)

    /** `commit()`, not `apply()`: the process is restarted right after this, and the new one must read the new value. */
    fun setUseFake(value: Boolean) {
        prefs.edit().putBoolean(KEY_USE_FAKE, value).commit()
    }

    companion object {
        const val PREFS = "backend"
        const val KEY_USE_FAKE = "use_fake"
    }
}

/**
 * The Developer section's Mock mode switch. [usesFake] is the mode this process runs in (read once, at start), which is
 * not necessarily what is stored: the stored choice only takes effect on the next start. [restart] is injected so tests
 * can observe it; the app passes [ProcessRestart.restart].
 */
class MockModeSwitch(val usesFake: Boolean, private val choice: BackendChoice, private val restart: () -> Unit) {
    /** Stores the new mode, then restarts, so the new process reads the new mode. */
    fun change(useFake: Boolean) {
        choice.setUseFake(useFake)
        restart()
    }
}
