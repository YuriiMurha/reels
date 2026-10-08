package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.session.RecordingCookieStore
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.SyncScheduler
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * H2 on the real screen: in Mock mode the Session section shows the REAL Pacer's state (the one Check now, the lab and the
 * video resolver use) under the session status; with the real backend it adds nothing, because the existing lines already
 * show that Pacer. How the container picks which ViewModel argument to pass is pinned in `BackendWiringGuardTest`.
 */
@RunWith(AndroidJUnit4::class)
class SyncScreenRealPacerNoteTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val settings by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
    }

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private object IdleScheduler : SyncScheduler {
        override fun enqueue(runId: Long) = Unit
        override fun cancel() = Unit
        override suspend fun isActive(): Boolean = false
    }

    private val probe = object : SessionProbe {
        override suspend fun currentUser() = Account("42", "tester")
    }

    /**
     * [mockMode] as the container wires it: the screen's own pacer is the fake library's (Fast, in memory) and the real one is
     * handed over as [SyncViewModel]'s `realPacer`; with the real backend the screen's pacer IS the real one and nothing extra is passed.
     */
    private fun show(mockMode: Boolean, cooldown: Boolean = false) {
        val realLog = InMemoryRequestLog(List(4) { System.currentTimeMillis() - 1_000 })
        val realCooldowns = InMemoryCooldownStore()
        if (cooldown) runBlocking { realCooldowns.onRateLimited(System.currentTimeMillis()) }
        val real = Pacer(PacingPolicy.Conservative, realLog, realCooldowns)
        val fake = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), InMemoryCooldownStore())
        val viewModel = SyncViewModel(
            SyncController(db, IdleScheduler),
            LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            if (mockMode) fake else real,
            SessionRepository(RecordingCookieStore(), probe, real, settings),
            requiresSession = !mockMode,
            realPacer = real.takeIf { mockMode },
        )
        compose.setContent {
            ReelsTheme {
                SyncScreen(onBack = {}, onOpenLogin = { _, _ -> }, onOpenLab = {}, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
    }

    /** The ViewModel's work resumes on the main looper, which only moves while the compose rule idles it. */
    private fun awaitUntil(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        condition()
    }

    private fun texts(text: String, substring: Boolean = false) = compose.onAllNodesWithText(text, substring = substring)

    @Test
    fun inMockModeTheRealPacersCountIsShownUnderTheSessionStatus() {
        show(mockMode = true)
        awaitUntil { texts("Instagram requests in 24 h: 4 / 600").fetchSemanticsNodes().isNotEmpty() }
        texts("Instagram requests in 24 h: 4 / 600").assertCountEquals(1)
        texts("Instagram requests paused", substring = true).assertCountEquals(0)
    }

    @Test
    fun inMockModeARealCooldownIsShownAsPaused() {
        show(mockMode = true, cooldown = true)
        awaitUntil { texts("Instagram requests paused", substring = true).fetchSemanticsNodes().isNotEmpty() }
        texts("Instagram requests paused: 60 min left (cooldown)").assertCountEquals(1)
        texts("Instagram requests in 24 h", substring = true).assertCountEquals(0)
    }

    @Test
    fun withTheRealBackendTheLineIsAbsentAndTheExistingOnesAreUnchanged() {
        show(mockMode = false)
        awaitUntil { texts("Log in").fetchSemanticsNodes().isNotEmpty() } // the stored session has been read
        // Give the 1 s ticker time to have shown it, had there been one: the VM's first tick is immediate.
        compose.waitForIdle()
        texts("Instagram requests", substring = true).assertCountEquals(0)
    }

    @Test
    fun withTheRealBackendACooldownStillShowsOnlyAsTheExistingBanner() {
        show(mockMode = false, cooldown = true)
        awaitUntil { texts("Cooling down after a rate limit", substring = true).fetchSemanticsNodes().isNotEmpty() }
        texts("Instagram requests", substring = true).assertCountEquals(0)
    }
}
