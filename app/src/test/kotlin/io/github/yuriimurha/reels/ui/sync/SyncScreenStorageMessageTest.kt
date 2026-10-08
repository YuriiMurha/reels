package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertTrue

/**
 * M6 on the real screen: a Delete library that leaves something behind says so next to the Delete library button (the Storage
 * section), not under the session status at the top.
 */
@RunWith(AndroidJUnit4::class)
class SyncScreenStorageMessageTest {
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

    private object IdleScheduler : SyncScheduler {
        override fun enqueue(runId: Long) = Unit
        override fun cancel() = Unit
        override suspend fun isActive(): Boolean = false
    }

    private fun show(forgetAccount: suspend () -> Unit = {}, clearVideoCache: () -> Unit = {}) {
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore())
        val probe = object : SessionProbe {
            override suspend fun currentUser() = Account("42", "tester")
        }
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        val viewModel = SyncViewModel(
            SyncController(db, IdleScheduler),
            LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs")), clearVideoCache = clearVideoCache, forgetAccount = forgetAccount),
            pacer,
            SessionRepository(RecordingCookieStore(), probe, pacer, settings),
            requiresSession = false,
        )
        compose.setContent {
            ReelsTheme { SyncScreen(onBack = {}, onOpenLogin = { _, _ -> }, onOpenLab = {}, viewModel = viewModel) }
        }
        compose.waitForIdle()
    }

    private fun awaitText(text: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    private fun deleteLibrary() {
        awaitText("Not logged in")
        compose.onNodeWithText("Delete library").performScrollTo().performClick()
        compose.onNodeWithText("Delete").performClick()
    }

    @Test
    fun anAccountRecordThatCouldNotBeClearedIsSaidUnderDeleteLibrary() {
        show(forgetAccount = { throw java.io.IOException("disk full") })
        deleteLibrary()
        val message = "Library deleted, but the account record couldn't be cleared; try Delete library again"
        awaitText(message)
        compose.onNodeWithText(message).performScrollTo()
        assertTrue(top("Delete library") < top(message), "under the button")
        assertTrue(top("Instagram session") < top("Delete library"), "and the session section is above, with none of it")
    }

    @Test
    fun cachedFilesThatCouldNotBeRemovedAreSaidUnderDeleteLibraryToo() {
        show(clearVideoCache = { throw java.io.IOException("disk error") })
        deleteLibrary()
        awaitText("Library deleted; some cached files couldn't be removed")
    }

    @Test
    fun aCleanDeleteSaysNothing() {
        show()
        deleteLibrary()
        compose.waitForIdle()
        compose.onAllNodesWithText("Library deleted", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Couldn't delete", substring = true).assertCountEquals(0)
    }
}
