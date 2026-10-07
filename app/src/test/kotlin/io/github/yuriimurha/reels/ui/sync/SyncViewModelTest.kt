package io.github.yuriimurha.reels.ui.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.di.BackendChoice
import io.github.yuriimurha.reels.di.MockModeSwitch
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.cookieValue
import io.github.yuriimurha.reels.session.RecordingCookieStore
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.SyncScheduler
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SyncViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val cooldowns = InMemoryCooldownStore()
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val cookies = RecordingCookieStore()
    private val probe = FakeProbe()
    private lateinit var session: SessionRepository

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        storeScope.cancel()
    }

    private object IdleScheduler : SyncScheduler {
        override fun enqueue(runId: Long) = Unit
        override fun cancel() = Unit
        override suspend fun isActive(): Boolean = false
    }

    /** The clock is the test scheduler's, so the Pacer, the ViewModel and the 1 s ticker agree on the time. */
    private fun kotlinx.coroutines.test.TestScope.viewModel(mockSwitch: MockModeSwitch? = null): SyncViewModel {
        val clock = { START + testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), cooldowns, now = clock)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        session = SessionRepository(cookies, probe, pacer, settings)
        return SyncViewModel(
            SyncController(db, IdleScheduler, now = clock),
            LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            pacer,
            session,
            mockSwitch = mockSwitch,
            now = clock,
            io = StandardTestDispatcher(testScheduler),
        )
    }

    /** A jar that already holds a session, as after a WebView login. Forgets the seeding writes so tests see only their own. */
    private fun signedIn(sessionId: String = "s1", userId: String = "41") {
        cookies.setCookie(SessionRepository.INSTAGRAM, "sessionid=$sessionId")
        cookies.setCookie(SessionRepository.INSTAGRAM, "ds_user_id=$userId")
        cookies.events.clear()
    }

    @Test
    fun theCooldownBannerCountsDownWhileTheScreenIsOpen() = runTest {
        // A rate limit an hour minus 90 s ago: the 1 h cooldown ends 90 s from now.
        cooldowns.onRateLimited(START - 3_600_000 + 90_000)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.ui.collect {} }
        runCurrent()
        assertEquals("Cooling down after a rate limit: 2 min left", viewModel.ui.value.banner)
        assertFalse(viewModel.ui.value.canStart)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("Cooling down after a rate limit: 1 min left", viewModel.ui.value.banner)
    }

    @Test
    fun theCooldownEndsOnItsOwnWhileTheScreenIsOpen() = runTest {
        cooldowns.onRateLimited(START - 3_600_000 + 90_000)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.ui.collect {} }
        runCurrent()
        assertFalse(viewModel.ui.value.canStart)

        advanceTimeBy(91_000)
        runCurrent()
        assertNull(viewModel.ui.value.banner)
        assertTrue(viewModel.ui.value.canStart)
    }

    @Test
    fun aValidPasteIsAcceptedAndSaysTheWebViewStillNeedsACsrfToken() = runTest {
        val viewModel = viewModel()
        val accepted = CompletableDeferred<Boolean>()
        viewModel.paste("42%3Aab") { accepted.complete(it) }
        assertTrue(accepted.await(), "no csrftoken in the jar yet")
        assertNull(viewModel.pasteError.value)
        assertEquals(SessionState.Valid("tester"), session.state.first { it is SessionState.Valid })
    }

    @Test
    fun aPasteIntoAJarThatAlreadyHasACsrfTokenNeedsNoWebViewVisit() = runTest {
        cookies.setCookie(SessionRepository.INSTAGRAM, "csrftoken=c1")
        val viewModel = viewModel()
        val accepted = CompletableDeferred<Boolean>()
        viewModel.paste("42%3Aab") { accepted.complete(it) }
        assertFalse(accepted.await())
    }

    @Test
    fun aRejectedPasteKeepsTheCurrentLoginAndNeverShowsTheOldHandle() = runTest {
        signedIn()
        probe.next = { Account("41", "old_account") }
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("old_account"), session.validate())
        probe.next = { throw InstagramException.LoginRequired() }
        var accepted = false
        viewModel.paste("43%3Acd") { accepted = true }
        val error = viewModel.pasteError.first { it != null }
        assertEquals("Instagram rejected that session; your current login is unchanged", error)
        assertFalse(error!!.contains("old_account"), "the rejected result carries the OLD account's handle")
        assertFalse(accepted, "only Valid counts as accepted")
        assertEquals("s1", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"), "the previous login must survive")
        assertEquals(SessionState.Valid("old_account"), session.state.first())
    }

    @Test
    fun aPasteThatTriggersAChallengeIsRejectedToo() = runTest {
        signedIn()
        val viewModel = viewModel()
        probe.next = { throw InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/") }
        var accepted = false
        viewModel.paste("43%3Acd") { accepted = true }
        assertEquals(
            "Instagram rejected that session; your current login is unchanged",
            viewModel.pasteError.first { it != null },
        )
        assertFalse(accepted)
    }

    @Test
    fun somethingThatIsNotASessionIdIsSaidSoWithoutARequest() = runTest {
        val viewModel = viewModel()
        var accepted = false
        viewModel.paste("hello there") { accepted = true }
        assertEquals("That doesn't look like a sessionid", viewModel.pasteError.first { it != null })
        assertFalse(accepted)
        assertEquals(0, probe.calls)
        assertEquals(emptyList(), cookies.events, "garbage must not touch the cookie jar")
    }

    @Test
    fun aPacerRefusalShowsItsMessageAndChangesNothing() = runTest {
        signedIn()
        cooldowns.onRateLimited(START)
        val viewModel = viewModel()
        var accepted = false
        viewModel.paste("43%3Acd") { accepted = true }
        assertEquals("Cooling down after a rate limit", viewModel.pasteError.first { it != null })
        assertFalse(accepted)
        assertEquals(0, probe.calls)
        assertEquals(emptyList(), cookies.events, "a refused paste must not touch the cookie jar")
    }

    @Test
    fun anUnexpectedFailureNeverShowsItsOwnText() = runTest {
        val viewModel = viewModel()
        probe.next = { throw IllegalStateException("an internal detail that must stay internal") }
        viewModel.paste("43%3Acd") {}
        assertEquals("Couldn't check that session", viewModel.pasteError.first { it != null })
    }

    @Test
    fun aSecondTapWhileThePasteIsBeingCheckedSendsNoSecondRequest() = runTest {
        val viewModel = viewModel()
        val gate = CompletableDeferred<Unit>()
        probe.gate = gate
        val accepted = CompletableDeferred<Boolean>()
        viewModel.paste("42%3Aab") { accepted.complete(it) }
        probe.entered.await()
        var secondAccepted = false
        viewModel.paste("42%3Aab") { secondAccepted = true }
        gate.complete(Unit)
        accepted.await()
        // The repository's mutex is FIFO, so once this returns, a second paste queued behind the first has finished too.
        session.logout()
        assertEquals(1, probe.calls, "a paste is an Instagram request: the second tap must be ignored")
        assertFalse(secondAccepted)
    }

    @Test
    fun clearingThePasteErrorForgetsIt() = runTest {
        val viewModel = viewModel()
        viewModel.paste("hello there") {}
        viewModel.pasteError.first { it != null }
        viewModel.clearPasteError()
        assertNull(viewModel.pasteError.value)
    }

    @Test
    fun checkNowReportsAFixedMessageAndTheNextSuccessClearsIt() = runTest {
        signedIn()
        val viewModel = viewModel()
        probe.next = { throw InstagramException.Transient() }
        viewModel.checkSession()
        assertEquals("Temporary network or server problem", viewModel.sessionMessage.first { it != null })
        probe.next = { Account("41", "tester") }
        backgroundScope.launch { viewModel.sessionState.collect {} }
        viewModel.checkSession()
        assertEquals(SessionState.Valid("tester"), viewModel.sessionState.first { it is SessionState.Valid })
        assertNull(viewModel.sessionMessage.value)
    }

    /**
     * A check's work hops to real I/O threads (the DataStore) and then waits out the Pacer's 2 s interactive gap in
     * virtual time. Alternate real time and virtual time so a check that was wrongly started has certainly reached the probe.
     */
    private suspend fun TestScope.settle() = repeat(5) {
        withContext(Dispatchers.Default) { delay(50) }
        advanceUntilIdle()
    }

    @Test
    fun aSecondCheckNowWhileOneIsOutSendsNoSecondRequest() = runTest {
        signedIn()
        val viewModel = viewModel()
        val gate = CompletableDeferred<Unit>()
        probe.gate = gate
        viewModel.checkSession()
        probe.entered.await()
        viewModel.checkSession()
        viewModel.checkSession()
        gate.complete(Unit)
        session.state.first { it is SessionState.Valid }
        settle()
        assertEquals(1, probe.calls, "a double tap on Check now must be one currentUser request")

        viewModel.checkSession()
        settle()
        assertEquals(2, probe.calls, "once the check is over, the next tap checks again")
    }

    @Test
    fun theSessionStateIsUnknownUntilTheStoredOneHasBeenRead() = runTest {
        val viewModel = viewModel()
        assertNull(viewModel.sessionState.value, "null means still loading: showing LoggedOut would offer Log in too early")
        backgroundScope.launch { viewModel.sessionState.collect {} }
        assertEquals(SessionState.LoggedOut, viewModel.sessionState.first { it != null })
    }

    @Test
    fun logoutForgetsTheSessionButNotTheLibrary() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())
        viewModel.logout()
        assertEquals(SessionState.LoggedOut, session.state.first { it == SessionState.LoggedOut })
        assertFalse(session.hasSessionCookies())
    }

    /** Every step the switch took, in order, with the stored mode at that moment. */
    private val switchEvents = mutableListOf<String>()

    private fun mockSwitch(usesFake: Boolean): MockModeSwitch {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE)
        val choice = BackendChoice(prefs, debugBuild = true)
        return MockModeSwitch(usesFake, choice, cancelSync = { switchEvents += "cancel" }) { switchEvents += "restart(useFake=${choice.useFake})" }
    }

    @Test
    fun withoutAMockSwitchThereIsNoMockMode() = runTest {
        val viewModel = viewModel()
        assertNull(viewModel.mockMode, "null hides the switch")
        viewModel.setMockMode(false) // nothing to change, and nothing may crash
    }

    @Test
    fun theMockModeIsTheOneTheProcessRunsIn() = runTest {
        assertEquals(true, viewModel(mockSwitch(usesFake = true)).mockMode)
        assertEquals(false, viewModel(mockSwitch(usesFake = false)).mockMode)
    }

    /** R67 (b): until the latest run has been read, "no run" can't be told from "not loaded yet", so the switch is off. */
    @Test
    fun theMockSwitchIsDisabledUntilTheLatestRunHasLoaded() = runTest {
        val viewModel = viewModel(mockSwitch(usesFake = true))
        assertFalse(viewModel.mockSwitchEnabled.value, "nothing has been read yet")
        viewModel.setMockMode(false)
        advanceUntilIdle()
        assertEquals(emptyList(), switchEvents, "refused while the run is loading")

        backgroundScope.launch { viewModel.mockSwitchEnabled.collect {} }
        assertTrue(viewModel.mockSwitchEnabled.first { it }, "loaded, and there is no run: enabled")
        viewModel.setMockMode(false)
        advanceUntilIdle()
        assertEquals(listOf("cancel", "restart(useFake=false)"), switchEvents, "the queued sync is cancelled, then the mode stored, then the restart")
    }

    /**
     * R70 (d): a screen that was stopped for longer than the 5 s grace period must not replay the run it had loaded. A run may
     * have started meanwhile, and a tap before the run has been read again would restart the app under it.
     */
    @Test
    fun aStoppedScreenDoesNotReplayAStaleLoadedRun() = runTest {
        val viewModel = viewModel(mockSwitch(usesFake = true))
        val screen = launch { viewModel.mockSwitchEnabled.collect {} }
        viewModel.mockSwitchEnabled.first { it } // loaded: there is no run, so the switch is on
        screen.cancel()
        runCurrent()
        db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = START)) // while stopped
        advanceTimeBy(11_000) // the switch flow's 5 s grace period, then the loaded-run flow's
        runCurrent()

        backgroundScope.launch { viewModel.mockSwitchEnabled.collect {} } // the screen is back; nothing has been read yet
        viewModel.setMockMode(false)
        advanceUntilIdle()

        assertEquals(emptyList(), switchEvents, "refused: the stale 'no run' must not be replayed")
    }

    @Test
    fun theMockModeIsNotChangedWhileARunIsRunning() = runTest {
        val viewModel = viewModel(mockSwitch(usesFake = true))
        val id = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = START))
        backgroundScope.launch { viewModel.mockSwitchEnabled.collect {} }
        viewModel.mockSwitchEnabled.first { it }

        db.syncDao().updateRun(db.syncDao().run(id)!!.copy(status = SyncStatus.RUNNING))
        viewModel.mockSwitchEnabled.first { !it }
        viewModel.setMockMode(false)
        advanceUntilIdle()

        assertEquals(emptyList(), switchEvents, "a restart would kill the run's process: the screen disables the switch and so does the ViewModel")
    }

    /** A double tap is one change: the second is ignored while the first is working. */
    @Test
    fun aDoubleTapOnTheMockSwitchChangesTheModeOnce() = runTest {
        val viewModel = viewModel(mockSwitch(usesFake = true))
        backgroundScope.launch { viewModel.mockSwitchEnabled.collect {} }
        viewModel.mockSwitchEnabled.first { it }

        viewModel.setMockMode(false)
        viewModel.setMockMode(false)
        advanceUntilIdle()

        assertEquals(listOf("cancel", "restart(useFake=false)"), switchEvents)
    }

    private class FakeProbe : SessionProbe {
        var calls = 0
        var next: () -> Account = { Account("42", "tester") }

        /** Completes when a call reaches the probe. */
        val entered = CompletableDeferred<Unit>()

        /** When set, the next call (only) waits for it after deciding its result. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun currentUser(): Account {
            calls++
            val result = next()
            entered.complete(Unit)
            gate?.also { gate = null }?.await()
            return result
        }
    }

    private companion object {
        const val START = 10_000_000L
    }
}
