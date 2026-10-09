package io.github.yuriimurha.reels.ui.sync

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.di.BackendChoice
import io.github.yuriimurha.reels.di.MockModeSwitch
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class DeveloperSectionTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    @After
    fun tearDown() {
        db.close()
        storeScope.cancel()
    }

    private var opened = 0
    private var forgotten = 0
    private val mockChanges = mutableListOf<Boolean>()

    private fun show(
        mockMode: Boolean? = null,
        mockSwitchEnabled: Boolean = true,
        labEnabled: Boolean = true,
        canForget: Boolean = false,
        forgetEnabled: Boolean = true,
        message: String? = null,
    ) = compose.setContent {
        ReelsTheme {
            DeveloperSection(
                mockMode = mockMode,
                mockSwitchEnabled = mockSwitchEnabled,
                onMockModeChange = { mockChanges += it },
                onOpenLab = { opened++ },
                labEnabled = labEnabled,
                onForgetQueryId = if (canForget) ({ forgotten++ }) else null,
                forgetEnabled = forgetEnabled,
                message = message,
            )
        }
    }

    /** Spec 2026-10-09 §3.3: the owner's way to watch one real repair. It sends nothing, so it needs no session. */
    @Test
    fun forgetCollectionsQueryIdIsOfferedWhenTheScreenGivesTheAction() {
        show(canForget = true, labEnabled = false)
        compose.onNodeWithText("Forget collections query id").assertIsEnabled().performClick()
        assertEquals(1, forgotten)
    }

    /** R21: while a run is RUNNING (or the run has not loaded yet) the screen turns it off, like the Mock mode switch. */
    @Test
    fun aForgetTheScreenTurnedOffCannotBeTapped() {
        show(canForget = true, forgetEnabled = false)
        compose.onNodeWithText("Forget collections query id").assertIsNotEnabled().performClick()
        assertEquals(0, forgotten)
    }

    @Test
    fun noForgetButtonWithoutTheAction() {
        show(canForget = false)
        compose.onAllNodesWithText("Forget collections query id").assertCountEquals(0)
    }

    @Test
    fun theSectionsMessageIsShown() {
        show(canForget = true, message = "Couldn't forget the collections query id")
        compose.onNodeWithText("Couldn't forget the collections query id").assertExists()
    }

    @Test
    fun offersTheAdapterLabAndOpensIt() {
        show()
        compose.onNodeWithText("Developer").assertExists()
        compose.onNodeWithText("Adapter lab").assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theLabButtonCanBeDisabled() {
        show(labEnabled = false)
        compose.onNodeWithText("Adapter lab").assertIsNotEnabled().performClick()
        assertEquals(0, opened)
    }

    /** A screen whose ViewModel has no [MockModeSwitch] passes null, and no switch is offered. */
    @Test
    fun noMockSwitchWhenTheModeIsUnknown() {
        show(mockMode = null)
        compose.onAllNodesWithText("Mock mode (fake library)").assertCountEquals(0)
    }

    @Test
    fun theMockSwitchShowsTheModeAndReportsAChange() {
        show(mockMode = true)
        compose.onNodeWithText("Mock mode (fake library)").assertIsEnabled().performClick()
        assertEquals(listOf(false), mockChanges)
        compose.onNodeWithText("The app restarts. Real and fake libraries are kept separately.").assertExists()
    }

    @Test
    fun theMockSwitchCanBeDisabled() {
        show(mockMode = false, mockSwitchEnabled = false)
        compose.onNodeWithText("Mock mode (fake library)").assertIsNotEnabled().performClick()
        assertEquals(emptyList(), mockChanges)
    }

    // The tests below run the real Sync screen: what decides when the switch is disabled is the screen's, not the section's.
    // `BuildConfig.DEBUG` is true in the debug unit tests, so the Developer section is there.

    private object IdleScheduler : SyncScheduler {
        override fun enqueue(runId: Long) = Unit
        override fun cancel() = Unit
        override suspend fun isActive(): Boolean = false
    }

    private val choice by lazy {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE)
        BackendChoice(prefs, debugBuild = true)
    }

    /** Each restart as it happened, with the stored choice at that moment: the new process must read the NEW choice. */
    private val restarts = CopyOnWriteArrayList<Boolean>() // the switch runs off the main thread

    private fun showSyncScreen(usesFake: Boolean, runStatus: SyncStatus?) {
        if (runStatus != null) {
            runBlocking { db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = runStatus, startedAt = 1)) }
        }
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore())
        val probe = object : SessionProbe {
            override suspend fun currentUser() = Account("42", "tester")
        }
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        val viewModel = SyncViewModel(
            SyncController(db, IdleScheduler),
            LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            pacer,
            SessionRepository(RecordingCookieStore(), probe, pacer, settings),
            requiresSession = false,
            mockSwitch = MockModeSwitch(usesFake, choice, cancelSync = {}) { restarts += choice.useFake },
        )
        compose.setContent {
            ReelsTheme { SyncScreen(onBack = {}, onOpenLogin = { _, _ -> }, onOpenLab = {}, viewModel = viewModel) }
        }
        compose.waitForIdle()
    }

    /** The run is read off the main thread; wait until the screen has it (a RUNNING run shows its "Syncing" section). */
    private fun awaitText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun mockSwitchDisabledWhileRunning() {
        showSyncScreen(usesFake = true, runStatus = SyncStatus.RUNNING)
        awaitText("Syncing")
        compose.onNodeWithText("Mock mode (fake library)").performScrollTo().assertIsNotEnabled().performClick()
        compose.waitForIdle()
        assertEquals(emptyList(), restarts, "no restart while a run is going")
        assertTrue(choice.useFake, "the stored choice is untouched")
    }

    /** The switch stays off until the latest run has been read (R67), so tests wait for it to come on. */
    private fun awaitSwitchEnabled() = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodes(hasText("Mock mode (fake library)") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitRestart() = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        restarts.isNotEmpty()
    }

    @Test
    fun mockSwitchOnTheSyncScreenStoresTheChoiceAndRestartsOnce() {
        showSyncScreen(usesFake = true, runStatus = null)
        awaitSwitchEnabled()
        compose.onNodeWithText("Mock mode (fake library)").performScrollTo().assertIsOn().performClick()
        awaitRestart()
        assertEquals(listOf(false), restarts, "one restart, after the new choice was stored")
        assertFalse(choice.useFake)
    }

    @Test
    fun theSwitchShowsTheModeTheProcessRunsIn() {
        showSyncScreen(usesFake = false, runStatus = null)
        awaitSwitchEnabled()
        // Off means real: the switch reads off, and one tap asks for the fake library.
        compose.onNodeWithText("Mock mode (fake library)").performScrollTo().assertIsOff().performClick()
        awaitRestart()
        assertEquals(listOf(true), restarts)
    }

    @Test
    fun aFinishedRunDoesNotDisableTheSwitch() {
        showSyncScreen(usesFake = true, runStatus = SyncStatus.DONE)
        awaitText("Last run")
        awaitSwitchEnabled()
    }
}
