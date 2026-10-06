package io.github.yuriimurha.reels.sync.pacing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class CooldownPersistenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun cooldownStore(file: File, scope: CoroutineScope) =
        DataStoreCooldownStore(SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file }))

    @Test
    fun cooldownSurvivesARestartAndEscalates() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")

        val firstScope = CoroutineScope(Dispatchers.IO + Job())
        val until = cooldownStore(file, firstScope).onRateLimited(now = 1_000)
        assertEquals(1_000 + Cooldowns.SHORT_MS, until)
        firstScope.coroutineContext.job.cancelAndJoin()

        val secondScope = CoroutineScope(Dispatchers.IO + Job())
        val restarted = cooldownStore(file, secondScope)
        assertEquals(until, restarted.activeUntil())
        assertEquals(2_000 + Cooldowns.LONG_MS, restarted.onRateLimited(now = 2_000))
        secondScope.coroutineContext.job.cancelAndJoin()
    }
}
