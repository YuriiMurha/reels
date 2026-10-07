package io.github.yuriimurha.reels.di

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R67 (c): cancel the queued sync, wait for it, then store the mode, and restart only if the mode was really stored. */
@RunWith(AndroidJUnit4::class)
class MockModeSwitchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs: SharedPreferences = context.getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE)
    private val choice = BackendChoice(prefs, debugBuild = true)
    private val events = mutableListOf<String>()

    /** Each step with the stored mode as it saw it, so the ORDER is observable. */
    private fun switch(
        choice: BackendChoice = this.choice,
        cancelSync: suspend () -> Unit = { events += "cancel(stored useFake=${this.choice.useFake})" },
    ) = MockModeSwitch(usesFake = true, choice = choice, cancelSync = cancelSync) { events += "restart(stored useFake=${this.choice.useFake})" }

    @Test
    fun cancelsTheQueuedSyncThenStoresTheModeThenRestarts() = runBlocking {
        assertTrue(switch().change(false))

        assertEquals(listOf("cancel(stored useFake=true)", "restart(stored useFake=false)"), events)
        assertFalse(prefs.getBoolean(BackendChoice.KEY_USE_FAKE, true))
    }

    /** A failed write must not restart into the same mode as if it had worked. */
    @Test
    fun doesNotRestartWhenTheCommitFails() = runBlocking {
        val failing = BackendChoice(FailingCommit(prefs), debugBuild = true)
        assertFalse(switch(choice = failing).change(false))

        assertEquals(listOf("cancel(stored useFake=true)"), events, "cancelled, but no restart")
        assertTrue(choice.useFake, "nothing was stored")
    }

    /** If the queued work could not be cancelled, the old mode stays: WorkManager would otherwise replay it in the new one. */
    @Test
    fun changesNothingWhenTheCancelFails() = runBlocking {
        val result = switch(cancelSync = { throw IOException("work database busy") }).change(false)

        assertFalse(result)
        assertEquals(emptyList(), events)
        assertTrue(choice.useFake)
    }

    @Test
    fun setUseFakeSaysWhetherItWasStored() {
        assertTrue(choice.setUseFake(false))
        assertFalse(BackendChoice(FailingCommit(prefs), debugBuild = true).setUseFake(true))
        assertFalse(choice.useFake, "the failed write changed nothing")
    }

    private class FailingCommit(private val real: SharedPreferences) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = FailingEditor(real.edit())
    }

    private class FailingEditor(private val real: SharedPreferences.Editor) : SharedPreferences.Editor by real {
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { real.putBoolean(key, value) }

        override fun commit(): Boolean = false
    }
}
