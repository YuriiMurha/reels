package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.session.RecordingCookieStore
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.session.toStored
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.SyncScheduler
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.ui.login.LoginPurpose
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the Sync screen asks the navigation to open (the real screen over a real SessionRepository with a fake probe).
 * The purpose decides what the login screen may spend an Instagram request on, so each button must pass its own.
 */
@RunWith(AndroidJUnit4::class)
class SyncScreenLoginTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val cookies = RecordingCookieStore()
    private val settings by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
    }
    private var opened: Pair<String?, LoginPurpose>? = null
    private var labOpened = 0

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

    private fun show() {
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore())
        val probe = object : SessionProbe {
            override suspend fun currentUser() = Account("42", "tester")
        }
        val viewModel = SyncViewModel(
            SyncController(db, IdleScheduler),
            LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            pacer,
            SessionRepository(cookies, probe, pacer, settings),
            requiresSession = false,
        )
        compose.setContent {
            ReelsTheme {
                SyncScreen(
                    onBack = {},
                    onOpenLogin = { url, purpose -> opened = url to purpose },
                    onOpenLab = { labOpened++ },
                    viewModel = viewModel,
                )
            }
        }
        compose.waitForIdle()
    }

    /** The ViewModel's work resumes on the main looper, which only moves while the compose rule idles it. */
    private fun awaitUntil(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        condition()
    }

    private fun awaitText(text: String) = awaitUntil { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun logInOpensTheLoginPageAsALogin() {
        show()
        awaitText("Log in") // the stored state is read off the main thread; until then the section offers no buttons
        compose.onNodeWithText("Log in").performClick()
        assertEquals(null to LoginPurpose.LOGIN, opened)
    }

    @Test
    fun resolveOnInstagramOpensItsChallengePageAsAChallenge() {
        runBlocking {
            settings.setSession(SessionState.Challenge("https://www.instagram.com/challenge/x/", "tester").toStored())
        }
        show()
        awaitText("Resolve on Instagram")
        compose.onNodeWithText("Resolve on Instagram").performClick()
        assertEquals("https://www.instagram.com/challenge/x/" to LoginPurpose.CHALLENGE, opened)
    }

    @Test
    fun logInAgainOpensTheLoginPageAsARelogin() {
        runBlocking { settings.setSession(SessionState.Expired("tester").toStored()) }
        show()
        awaitText("Log in again")
        compose.onNodeWithText("Log in again").performClick()
        assertEquals(null to LoginPurpose.RELOGIN, opened)
    }

    private fun paste() {
        awaitText("Paste sessionid")
        compose.onNodeWithText("Paste sessionid").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).performTextInput("42%3Aab")
        compose.onNodeWithText("Use it").performClick()
    }

    /** `BuildConfig.DEBUG` is true in the debug unit tests, so the Developer section is there. */
    @Test
    fun theDeveloperSectionOffersTheAdapterLabWhileLoggedIn() {
        runBlocking { settings.setSession(SessionState.Valid("tester").toStored()) }
        show()
        awaitText("Adapter lab")
        compose.onNodeWithText("Developer").assertExists()
        compose.onNodeWithText("Adapter lab").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, labOpened)
        // This ViewModel has no MockModeSwitch, which hides the switch.
        compose.onAllNodesWithText("Mock mode (fake library)").assertCountEquals(0)
    }

    @Test
    fun theAdapterLabNeedsAValidSession() {
        show()
        awaitText("Log in") // the stored state has been read: LoggedOut
        compose.onNodeWithText("Adapter lab").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, labOpened)
    }

    @Test
    fun anAcceptedPasteWithNoCsrfTokenOpensInstagramHomeAsCsrf() {
        show()
        paste()
        awaitUntil { opened != null }
        assertEquals("https://www.instagram.com/" to LoginPurpose.CSRF, opened)
    }

    @Test
    fun anAcceptedPasteThatAlreadyHasACsrfTokenOpensNothing() {
        cookies.setCookie(SessionRepository.INSTAGRAM, "csrftoken=c1")
        show()
        paste()
        awaitUntil { compose.onAllNodesWithText("Use it").fetchSemanticsNodes().isEmpty() }
        assertNull(opened)
    }
}
