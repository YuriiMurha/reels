package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Spec 2026-10-09 §3.3 on the real Sync screen: the real backend's Developer section offers "Forget collections query id" (off
 * while a run is RUNNING) and the screen says "Couldn't refresh collection names" while the names are the last good ones. That
 * Mock mode gets neither (no action, no flag) is the screen's wiring, pinned in `BackendWiringGuardTest`: a test here could only
 * check this file's own helper. `BuildConfig.DEBUG` is true in the debug unit tests, so the Developer section is there.
 */
@RunWith(AndroidJUnit4::class)
class SyncScreenCollectionNamesTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val settings by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
    }
    private val query = WebGraphQl.SAVED_COLLECTIONS.friendlyName

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

    /** [realBackend] as the screen wires it: the settings' action and flag for the real backend, nothing for Mock mode. */
    private fun show(realBackend: Boolean) {
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore())
        val viewModel = SyncViewModel(
            SyncController(db, IdleScheduler),
            LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            pacer,
            SessionRepository(RecordingCookieStore(), probe, pacer, settings),
            requiresSession = realBackend,
            forgetCollectionsQueryId = if (realBackend) settings::forgetCollectionsQueryId else null,
            collectionNamesStale = if (realBackend) settings.collectionNamesStale else flowOf(false),
        )
        compose.setContent {
            ReelsTheme { SyncScreen(onBack = {}, onOpenLogin = { _, _ -> }, onOpenLab = {}, viewModel = viewModel) }
        }
        compose.waitForIdle()
    }

    /** The ViewModel's work resumes on the main looper, which only moves while the compose rule idles it. */
    private fun awaitUntil(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        condition()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theRealBackendOffersForgetAndATapArmsOneRepair() {
        runBlocking {
            settings.setGraphqlDocId(query, "777")
            settings.setCollectionsRepairAt(1_000L)
        }
        show(realBackend = true)
        awaitUntil { shown("Forget collections query id") }
        // On once the latest run has been read (there is none).
        awaitUntil { compose.onAllNodes(hasText("Forget collections query id") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Forget collections query id").performScrollTo().assertIsEnabled().performClick()

        awaitUntil { runBlocking { settings.collectionsForceRepair() } }
        assertNull(runBlocking { settings.collectionsRepairAt() })
        assertEquals("777", runBlocking { settings.graphqlDocId(query) }, "no made-up id is stored")
    }

    /** R21: while the latest run is RUNNING the button is there but off. */
    @Test
    fun theForgetButtonIsOffWhileARunIsRunning() {
        runBlocking {
            db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = 1L))
            settings.setCollectionsRepairAt(1_000L)
        }
        show(realBackend = true)
        awaitUntil { shown("Syncing") } // the running run has been read

        compose.onNodeWithText("Forget collections query id").performScrollTo().assertIsNotEnabled().performClick()

        compose.waitForIdle()
        assertEquals(false, runBlocking { settings.collectionsForceRepair() })
        assertEquals(1_000L, runBlocking { settings.collectionsRepairAt() }, "the running repair's attempt record is kept")
    }

    /** The screen given no action (as Mock mode's is: the wiring is pinned in `BackendWiringGuardTest`) shows no button. */
    @Test
    fun noActionOffersNoForgetButton() {
        show(realBackend = false)
        awaitUntil { shown("Adapter lab") }
        compose.onAllNodesWithText("Forget collections query id").assertCountEquals(0)
    }

    @Test
    fun theNoticeIsShownWhileTheNamesAreStaleAndGoesWhenTheyAreFresh() {
        runBlocking { settings.setCollectionNamesStale(true) }
        show(realBackend = true)
        awaitUntil { shown("Couldn't refresh collection names") }

        runBlocking { settings.setCollectionNamesStale(false) }

        awaitUntil { !shown("Couldn't refresh collection names") }
    }

    @Test
    fun noNoticeWhileTheNamesAreFresh() {
        show(realBackend = true)
        awaitUntil { shown("Log in") } // the stored state has been read
        compose.waitForIdle()
        compose.onAllNodesWithText("Couldn't refresh collection names").assertCountEquals(0)
    }
}
