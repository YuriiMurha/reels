package io.github.yuriimurha.reels.ui.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import androidx.lifecycle.viewModelScope
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
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /** When set, the scheduler's [SyncScheduler.cancel] throws it, as a WorkManager failure would. */
    private var cancelFailure: Exception? = null

    private val enqueued = CopyOnWriteArrayList<Long>()

    /** Writes "scheduler.cancel" into the cookie jar's event list, so a test sees it in order with the jar's own "clear". */
    private val scheduler = object : SyncScheduler {
        override fun enqueue(runId: Long) {
            enqueued += runId
        }
        override fun cancel() {
            cookies.events += "scheduler.cancel"
            cancelFailure?.let { throw it }
        }
        override suspend fun isActive(): Boolean = false
    }

    /** The clock is the test scheduler's, so the Pacer, the ViewModel and the 1 s ticker agree on the time. */
    private fun kotlinx.coroutines.test.TestScope.viewModel(
        mockSwitch: MockModeSwitch? = null,
        requiresSession: Boolean = false,
        realPacer: Pacer? = null,
        forgetAccount: suspend () -> Unit = {},
        clearVideoCache: () -> Unit = {},
        /** The collections query's Developer action and names notice, as the screen wires them for the real backend. */
        realCollections: Boolean = false,
    ): SyncViewModel {
        val clock = { START + testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), cooldowns, now = clock)
        val file = PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") }
        settings = SettingsStore(FlakyDataStore(file) { storageFailure })
        session = SessionRepository(cookies, probe, pacer, settings)
        return SyncViewModel(
            SyncController(db, scheduler, now = clock),
            LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs")), clearVideoCache = clearVideoCache, forgetAccount = forgetAccount),
            pacer,
            session,
            requiresSession = requiresSession,
            mockSwitch = mockSwitch,
            realPacer = realPacer,
            forgetCollectionsQueryId = if (realCollections) settings::forgetCollectionsQueryId else null,
            collectionNamesStale = if (realCollections) settings.collectionNamesStale else flowOf(false),
            now = clock,
            io = StandardTestDispatcher(testScheduler),
        )
    }

    /** The settings of the ViewModel built last. */
    private lateinit var settings: SettingsStore

    /** The process's real Pacer as Mock mode sees it: its own log and cooldown, on the same clock as the ViewModel. */
    private fun kotlinx.coroutines.test.TestScope.realPacer(log: InMemoryRequestLog, cooldowns: InMemoryCooldownStore) =
        Pacer(PacingPolicy.Conservative, log, cooldowns, now = { START + testScheduler.currentTime })

    /** While set, every write to the settings file throws it, as a full disk would. Reads still work. */
    private var storageFailure: Exception? = null

    private class FlakyDataStore(private val inner: DataStore<Preferences>, private val failure: () -> Exception?) : DataStore<Preferences> {
        override val data get() = inner.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            failure()?.let { throw it }
            return inner.updateData(transform)
        }
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

    // ---- Spec 2026-10-09 §3.3: Forget collections query id, and the names notice ----

    @Test
    fun forgetQueryIdStoresAnIdTheSiteNeverRunsAndClearsTheRepairLimit() = runTest {
        val viewModel = viewModel(realCollections = true)
        val query = WebGraphQl.SAVED_COLLECTIONS.friendlyName
        settings.setGraphqlDocId(query, "777")
        settings.setCollectionsRepairAt(START - 60_000)
        assertTrue(viewModel.canForgetQueryId)

        viewModel.forgetQueryId()
        advanceUntilIdle()
        awaitStored { settings.graphqlDocId(query) == "0" }

        assertEquals("0", settings.graphqlDocId(query), "so the next use is stale and repairs")
        assertNull(settings.collectionsRepairAt(), "so that repair is not refused by the last one's 24 h")
        assertNull(viewModel.developerMessage.value)
        assertEquals(0, probe.calls, "it sends nothing")
    }

    /** Waits, in real time (DataStore writes on a thread of its own), until [condition] holds. */
    private suspend fun awaitStored(condition: suspend () -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    /** Mock mode's fake library has no query to forget: the ViewModel offers no action and does nothing. */
    @Test
    fun withoutTheActionForgetDoesNothing() = runTest {
        val viewModel = viewModel()
        settings.setGraphqlDocId(WebGraphQl.SAVED_COLLECTIONS.friendlyName, "777")
        settings.setCollectionsRepairAt(START)
        assertFalse(viewModel.canForgetQueryId)

        viewModel.forgetQueryId()
        advanceUntilIdle()

        assertEquals("777", settings.graphqlDocId(WebGraphQl.SAVED_COLLECTIONS.friendlyName))
        assertEquals(START, settings.collectionsRepairAt())
    }

    /** A write that fails is said under the button, never thrown: an exception out of this scope ends the app. */
    @Test
    fun aForgetThatCannotBeWrittenIsSaidOnTheScreen() = runTest {
        val viewModel = viewModel(realCollections = true)
        settings.setGraphqlDocId(WebGraphQl.SAVED_COLLECTIONS.friendlyName, "777")
        storageFailure = java.io.IOException("disk full at /data/user/0")

        viewModel.forgetQueryId()
        advanceUntilIdle()

        assertEquals("Couldn't forget the collections query id", viewModel.developerMessage.value)
        assertEquals("777", settings.graphqlDocId(WebGraphQl.SAVED_COLLECTIONS.friendlyName))

        storageFailure = null
        viewModel.forgetQueryId()
        advanceUntilIdle()
        awaitStored { settings.graphqlDocId(WebGraphQl.SAVED_COLLECTIONS.friendlyName) == "0" }
        assertNull(viewModel.developerMessage.value, "a tap that works clears it")
    }

    @Test
    fun theNamesNoticeFollowsTheStoredFlag() = runTest {
        val viewModel = viewModel(realCollections = true)
        backgroundScope.launch { viewModel.ui.collect {} }
        runCurrent()
        assertNull(viewModel.ui.value.collectionNamesNotice)

        settings.setCollectionNamesStale(true)
        assertEquals("Couldn't refresh collection names", viewModel.ui.first { it.collectionNamesNotice != null }.collectionNamesNotice)

        settings.setCollectionNamesStale(false)
        viewModel.ui.first { it.collectionNamesNotice == null }
    }

    /** Mock mode's screen shows the fake library: a stale flag the real library left behind is not its notice. */
    @Test
    fun withoutTheFlagTheNoticeIsNeverShown() = runTest {
        val viewModel = viewModel()
        settings.setCollectionNamesStale(true)
        backgroundScope.launch { viewModel.ui.collect {} }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(viewModel.ui.value.collectionNamesNotice)
    }

    // ---- H2: Mock mode shows the REAL Pacer's state as one line ----

    @Test
    fun inMockModeTheRealPacersRequestCountIsShown() = runTest {
        val real = realPacer(InMemoryRequestLog(List(7) { START - 60_000 }), InMemoryCooldownStore())
        val viewModel = viewModel(realPacer = real)
        backgroundScope.launch { viewModel.realPacerNote.collect {} }
        runCurrent()
        assertEquals("Instagram requests in 24 h: 7 / 600", viewModel.realPacerNote.value)
    }

    @Test
    fun inMockModeARealCooldownIsShownAndCountsDown() = runTest {
        // A rate limit an hour minus 90 s ago: the real 1 h cooldown ends 90 s from now.
        val realCooldowns = InMemoryCooldownStore().apply { onRateLimited(START - 3_600_000 + 90_000) }
        val viewModel = viewModel(realPacer = realPacer(InMemoryRequestLog(List(7) { START - 60_000 }), realCooldowns))
        backgroundScope.launch { viewModel.realPacerNote.collect {} }
        runCurrent()
        assertEquals("Instagram requests paused: 2 min left (cooldown)", viewModel.realPacerNote.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("Instagram requests paused: 1 min left (cooldown)", viewModel.realPacerNote.value)

        advanceTimeBy(31_000)
        runCurrent()
        assertEquals("Instagram requests in 24 h: 7 / 600", viewModel.realPacerNote.value, "the cooldown ended on its own")
    }

    /** Fake syncs never touch Instagram, so a real cooldown must neither block them nor become their banner. */
    @Test
    fun aRealCooldownDoesNotBlockTheFakeLibrarysSync() = runTest {
        val realCooldowns = InMemoryCooldownStore().apply { onRateLimited(START) }
        val viewModel = viewModel(realPacer = realPacer(InMemoryRequestLog(), realCooldowns))
        backgroundScope.launch { viewModel.ui.collect {} }
        backgroundScope.launch { viewModel.realPacerNote.collect {} }
        runCurrent()
        assertEquals("Instagram requests paused: 60 min left (cooldown)", viewModel.realPacerNote.value)
        assertTrue(viewModel.ui.value.canStart)
        assertNull(viewModel.ui.value.banner)
    }

    /** And the other way round: the line reads the real Pacer, not the one the ViewModel's own Sync controls use. */
    @Test
    fun theLineReadsTheRealPacerNotTheFakeOne() = runTest {
        cooldowns.onRateLimited(START) // the ViewModel's own (fake) pacer is cooling down
        val viewModel = viewModel(realPacer = realPacer(InMemoryRequestLog(List(2) { START - 1_000 }), InMemoryCooldownStore()))
        backgroundScope.launch { viewModel.ui.collect {} }
        backgroundScope.launch { viewModel.realPacerNote.collect {} }
        runCurrent()
        assertEquals("Instagram requests in 24 h: 2 / 600", viewModel.realPacerNote.value)
        assertEquals("Cooling down after a rate limit: 60 min left", viewModel.ui.value.banner)
    }

    @Test
    fun withTheRealBackendThereIsNoExtraLine() = runTest {
        val viewModel = viewModel(requiresSession = true)
        backgroundScope.launch { viewModel.realPacerNote.collect {} }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(viewModel.realPacerNote.value)
    }

    /** `status()` only reads: showing the line sends nothing and records nothing, however long the screen stays open. */
    @Test
    fun showingTheLineMakesNoRequestAndRecordsNone() = runTest {
        val log = InMemoryRequestLog(List(3) { START - 1_000 })
        val viewModel = viewModel(realPacer = realPacer(log, InMemoryCooldownStore()))
        backgroundScope.launch { viewModel.realPacerNote.collect {} }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("Instagram requests in 24 h: 3 / 600", viewModel.realPacerNote.value)
        assertEquals(3, log.countSince(-1), "nothing was recorded")
        assertEquals(0, probe.calls, "and nothing was sent")
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
    fun logoutCancelsTheRunThenLogsOut() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())
        val running = db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = START))
        cookies.events.clear()

        viewModel.logout()
        session.state.first { it == SessionState.LoggedOut }

        assertEquals(
            listOf("scheduler.cancel", "clear"),
            cookies.events,
            "the run is asked to stop first, then the session is forgotten: the other order lets the run keep working with a session that is going away",
        )
        val stopped = db.syncDao().run(running)!!
        assertEquals(SyncStatus.PAUSED, stopped.status, "the run is left resumable, not running")
        assertEquals("Cancelled", stopped.lastError)
    }

    /** Whatever goes wrong while the run is being cancelled, the owner asked to log out: the session is forgotten. */
    @Test
    fun logoutForgetsTheSessionEvenWhenCancellingTheRunFails() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())
        cancelFailure = IllegalStateException("WorkManager is not available")
        cookies.events.clear()

        viewModel.logout()
        awaitLoggedOut()

        assertEquals(listOf("scheduler.cancel", "clear"), cookies.events, "the cancel was tried first, and its failure did not stop the logout")
        assertFalse(session.hasSessionCookies())
    }

    /**
     * A CancellationException from inside the cancel (a WorkManager future that was cancelled, say) is not this scope being
     * cancelled: the scope is shielded. The owner asked to log out, so the session is forgotten all the same.
     */
    @Test
    fun logoutForgetsTheSessionEvenWhenCancellingTheRunThrowsACancellation() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())
        cancelFailure = CancellationException("the scheduler's own future was cancelled")
        cookies.events.clear()

        viewModel.logout()
        awaitLoggedOut()

        assertEquals(listOf("scheduler.cancel", "clear"), cookies.events, "the cancel was tried first, and its cancellation did not stop the logout")
        assertFalse(session.hasSessionCookies(), "the jar is cleared")
    }

    /**
     * Waits (in real time, off the virtual clock) until the stored session reads LoggedOut. It re-reads the stored value
     * instead of waiting for an emission: a collector that subscribes while the logout's DataStore write lands can miss
     * that one update and then wait for ever (seen here as a 5 s timeout while the stored value already was LoggedOut,
     * about one run in ten with the old `first { ... }`).
     */
    private suspend fun awaitLoggedOut() {
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (session.state.first() != SessionState.LoggedOut) delay(10) }
        }
    }

    /** The screen can go away (the ViewModel is cleared, its scope cancelled) between the tap and the end of the logout. */
    @Test
    fun logoutCompletesEvenIfTheScreenGoesAwayRightAfterTheTap() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())
        db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = START))

        viewModel.logout()
        runCurrent() // the logout has started and is waiting on the database write that pauses the run
        viewModel.viewModelScope.cancel()
        assertFalse(viewModel.viewModelScope.isActive)
        awaitLoggedOut()

        assertFalse(session.hasSessionCookies())
        assertEquals(SyncStatus.PAUSED, db.syncDao().latestRun()!!.status, "the run was cancelled too")
    }

    // ---- H4: a storage failure during Logout or Delete library never crashes the app ----
    //
    // "Never crashes": an exception that escapes `viewModelScope.launch` reaches the uncaught-exception handler, which ends the
    // process on a phone. Under `runTest` it is collected and fails the test (before the fix it shows as the Suppressed
    // IOException of the failing assertion), so each test below passes only if nothing escapes.

    /** The detail an exception carries (here a session value) must never reach the screen. */
    private val secretDetail = "disk full while writing sessionid=" + "s1"

    @Test
    fun aStorageFailureDuringLogoutSaysSoAndLeavesNoSessionInTheJar() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())
        storageFailure = java.io.IOException(secretDetail)
        cookies.events.clear()

        viewModel.logout() // before the fix this throws inside viewModelScope.launch: the app's uncaught handler, i.e. a crash
        val message = messageOf(viewModel)

        assertEquals("Couldn't finish logging out; try again", message)
        assertFalse(secretDetail in message!!)
        assertFalse(session.hasSessionCookies(), "the cookies were cleared before the write failed, and stay cleared")
        assertEquals(listOf("scheduler.cancel", "clear"), cookies.events)
    }

    @Test
    fun logoutCanBeRetriedAfterAStorageFailureAndTheMessageGoes() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())
        storageFailure = java.io.IOException(secretDetail)
        viewModel.logout()
        assertNotNull(messageOf(viewModel), "no message was shown")
        assertEquals(SessionState.Valid("tester"), session.state.first(), "the stored state could not be written")

        storageFailure = null
        viewModel.logout()
        awaitLoggedOut()

        assertNull(viewModel.sessionMessage.value, "the retry cleared the message")
        assertFalse(session.hasSessionCookies())
    }

    /** The Storage section's own message line: the Delete library button is down there, not under the session status. */
    private suspend fun storageMessageOf(viewModel: SyncViewModel): String? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(3_000) {
            while (viewModel.storageMessage.value == null) delay(10)
            viewModel.storageMessage.value
        }
    }

    @Test
    fun aStorageFailureDuringDeleteLibraryKeepingTheAccountRecordSaysSoAndNothingElseIsLeft() = runTest {
        val viewModel = viewModel(forgetAccount = { throw java.io.IOException(secretDetail) })
        db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = START))

        viewModel.deleteLibrary() // before the fix the failure escapes viewModelScope.launch: a crash
        val message = storageMessageOf(viewModel)

        assertEquals("Library deleted, but the account record couldn't be cleared; try Delete library again", message)
        assertFalse(secretDetail in message!!)
        assertNull(db.syncDao().latestRun(), "the rows are gone")
        assertNull(viewModel.sessionMessage.value, "and nothing is said under the session status")
    }

    /** Only cached files are left: the library IS deleted, so it must not say it couldn't be. */
    @Test
    fun cachedFilesThatCouldNotBeRemovedAreNotSaidToBeAFailedDelete() = runTest {
        val viewModel = viewModel(clearVideoCache = { throw java.io.IOException(secretDetail) })
        db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.DONE, startedAt = START))

        viewModel.deleteLibrary()
        val message = storageMessageOf(viewModel)

        assertEquals("Library deleted; some cached files couldn't be removed", message)
        assertFalse(secretDetail in message!!)
        assertNull(db.syncDao().latestRun(), "the rows are gone")
    }

    /** Both failed: the account record is the one to act on (a second Delete library redoes both). */
    @Test
    fun anAccountRecordAndCachedFilesThatBothFailSayTheAccountRecord() = runTest {
        val viewModel = viewModel(forgetAccount = { throw java.io.IOException(secretDetail) }, clearVideoCache = { throw java.io.IOException(secretDetail) })

        viewModel.deleteLibrary()

        assertEquals("Library deleted, but the account record couldn't be cleared; try Delete library again", storageMessageOf(viewModel))
    }

    @Test
    fun deleteLibraryThatCompletesLeavesNoMessageAndClearsAnOldOne() = runTest {
        var failing = true
        val viewModel = viewModel(forgetAccount = { if (failing) throw java.io.IOException(secretDetail) })
        viewModel.deleteLibrary()
        assertNotNull(storageMessageOf(viewModel), "precondition: a message is up")

        failing = false
        viewModel.deleteLibrary()
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (viewModel.storageMessage.value != null) delay(10) } }

        assertNull(viewModel.storageMessage.value)
        assertNull(viewModel.sessionMessage.value)
    }

    /**
     * The line is cleared when a Delete library STARTS, not only when it ends: a second delete that is still under way must not sit
     * under the old failure's wording. The second delete would end with no message anyway (it succeeds), so only looking while it
     * is suspended, here inside the account-record step that follows the rows, tells clearing at the start from clearing at the end.
     */
    @Test
    fun theStorageMessageIsClearedWhenTheNextDeleteLibraryStartsNotWhenItEnds() = runTest {
        var failing = true
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val viewModel = viewModel(forgetAccount = {
            if (failing) throw java.io.IOException(secretDetail)
            reached.complete(Unit)
            release.await()
        })
        viewModel.deleteLibrary()
        assertEquals(ACCOUNT_RECORD_KEPT, storageMessageOf(viewModel), "precondition: a failed delete left its message up")

        failing = false
        val scope = viewModel.viewModelScope.coroutineContext[Job]!!
        val before = scope.children.toSet()
        viewModel.deleteLibrary()
        val second = scope.children.single { it !in before } // the screen's other flows live in this scope too
        withContext(Dispatchers.Default) { withTimeout(5_000) { reached.await() } }

        assertTrue(second.isActive, "precondition: the second delete is still under way")
        assertNull(viewModel.storageMessage.value, "the second delete is suspended mid-way: the old message is already gone")

        release.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5_000) { second.join() } }
        assertNull(viewModel.storageMessage.value, "and the delete that then completes leaves none")
    }

    /** Any other failure of the delete (here the database is closed under it) is shown too, in words that carry no detail. */
    @Test
    fun aFailureOfTheDeleteItselfIsShownAndNeverCrashes() = runTest {
        val viewModel = viewModel()
        db.close()

        viewModel.deleteLibrary()
        val message = storageMessageOf(viewModel)

        assertEquals("Couldn't delete the library; try again", message)
        assertNull(viewModel.sessionMessage.value)
    }

    /** Logout's failure stays under the session status, and does not touch the storage line. */
    @Test
    fun aLogoutFailureIsNotShownInTheStorageSection() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())
        storageFailure = java.io.IOException(secretDetail)

        viewModel.logout()

        assertEquals("Couldn't finish logging out; try again", messageOf(viewModel))
        assertNull(viewModel.storageMessage.value)
    }

    /** The screen's message, waited for in real time (the work hops to I/O threads); null if none came within 3 s. */
    private suspend fun messageOf(viewModel: SyncViewModel): String? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(3_000) {
            while (viewModel.sessionMessage.value == null) delay(10)
            viewModel.sessionMessage.value
        }
    }

    @Test
    fun logoutWithNothingRunningStillForgetsTheSession() = runTest {
        signedIn()
        val viewModel = viewModel()
        assertEquals(SessionState.Valid("tester"), session.validate())

        viewModel.logout()
        session.state.first { it == SessionState.LoggedOut }

        assertFalse(session.hasSessionCookies())
    }

    /**
     * The screen's state right now. `ui` is collected only for this instant, never across a wait for real I/O: while it is
     * collected, its 1 s ticker spins the test's virtual clock flat out, and a DataStore update that lands meanwhile on an
     * I/O thread can go unseen (the test then hangs; seen as an `UncompletedCoroutinesError`, about one run in ten on a cold
     * JVM). So the stored session is awaited through [SyncViewModel.sessionState], which has no ticker, and `ui` is read after.
     */
    private fun TestScope.screen(viewModel: SyncViewModel): SyncUiState {
        val open = launch { viewModel.ui.collect {} }
        runCurrent()
        val state = viewModel.ui.value
        open.cancel()
        return state
    }

    /** Presses Sync (or Full sync) while the screen is open, so `ui`, which `start` consults, is live. */
    private fun TestScope.startWithTheScreenOpen(viewModel: SyncViewModel, mode: SyncMode = SyncMode.QUICK) {
        val open = launch { viewModel.ui.collect {} }
        runCurrent()
        viewModel.start(mode)
        open.cancel()
    }

    /** Keeps the stored session loaded and returns once it has been read. */
    private suspend fun TestScope.loadSession(viewModel: SyncViewModel) {
        backgroundScope.launch { viewModel.sessionState.collect {} }
        viewModel.sessionState.first { it != null }
    }

    @Test
    fun realBackendNeedsAValidSessionToStart() = runTest {
        signedIn()
        val viewModel = viewModel(requiresSession = true)
        assertFalse(viewModel.ui.value.canStart, "before the stored session has been read it is unknown, and unknown is not ready")
        assertNull(viewModel.ui.value.banner, "but loading is not 'logged out': no \"Log in\" hint is flashed before the state is known")
        loadSession(viewModel)
        screen(viewModel).let {
            assertFalse(it.canStart, "logged out: Sync is off")
            assertEquals("Log in to Instagram to sync", it.banner)
        }

        assertEquals(SessionState.Valid("tester"), session.validate())
        viewModel.sessionState.first { it is SessionState.Valid }
        screen(viewModel).let {
            assertTrue(it.canStart)
            assertNull(it.banner, "a valid session needs no explanation")
        }

        probe.next = { throw InstagramException.LoginRequired() }
        assertEquals(SessionState.Expired("tester"), session.validate())
        viewModel.sessionState.first { it is SessionState.Expired }
        assertFalse(screen(viewModel).canStart, "an expired session cannot sync")

        probe.next = { Account("41", "tester") }
        session.validate()
        viewModel.sessionState.first { it is SessionState.Valid }
        assertTrue(screen(viewModel).canStart)

        viewModel.logout()
        viewModel.sessionState.first { it == SessionState.LoggedOut }
        screen(viewModel).let {
            assertFalse(it.canStart, "logged out again: Sync is off")
            assertEquals("Log in to Instagram to sync", it.banner)
        }
    }

    @Test
    fun aChallengedSessionCannotStartASyncEither() = runTest {
        signedIn()
        val viewModel = viewModel(requiresSession = true)
        loadSession(viewModel)
        probe.next = { throw InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/") }
        assertEquals(SessionState.Challenge("https://www.instagram.com/challenge/x/", null), session.validate())
        viewModel.sessionState.first { it is SessionState.Challenge }
        screen(viewModel).let {
            assertFalse(it.canStart)
            assertEquals(
                LOG_IN_TO_SYNC,
                it.banner,
                "the loaded state's banner (a challenged session is not ready), not the null of still loading: the snapshot is provably taken after the load",
            )
        }
    }

    /** Defence in depth: the buttons are disabled, but nothing that reaches `start` may begin a sync the screen did not offer. */
    @Test
    fun startDoesNothingUntilTheSessionIsValid() = runTest {
        signedIn()
        val viewModel = viewModel(requiresSession = true)
        loadSession(viewModel)

        startWithTheScreenOpen(viewModel)
        // Not advanceUntilIdle: it can't settle while the screen's ticker is alive. Let any (wrongly) started work finish.
        repeat(3) {
            runCurrent()
            withContext(Dispatchers.Default) { delay(100) }
        }
        assertNull(db.syncDao().latestRun(), "no run is created for a session that is not valid")
        assertEquals(emptyList(), enqueued)

        assertEquals(SessionState.Valid("tester"), session.validate())
        viewModel.sessionState.first { it is SessionState.Valid }
        startWithTheScreenOpen(viewModel)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (enqueued.isEmpty()) delay(10) } }
        assertEquals(1, enqueued.size, "once the screen offers Sync, the same call starts a run")
    }

    @Test
    fun theFakeBackendStartsWithoutASession() = runTest {
        val viewModel = viewModel(requiresSession = false)
        loadSession(viewModel)

        startWithTheScreenOpen(viewModel)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (enqueued.isEmpty()) delay(10) } }

        assertEquals(1, enqueued.size)
    }

    @Test
    fun theFakeBackendNeedsNoSessionToStart() = runTest {
        val viewModel = viewModel(requiresSession = false)
        assertTrue(viewModel.ui.value.canStart)
        backgroundScope.launch { viewModel.sessionState.collect {} }
        assertEquals(SessionState.LoggedOut, viewModel.sessionState.first { it != null })
        val shown = screen(viewModel)
        assertTrue(shown.canStart, "Mock mode never touches Instagram, so a logged-out app can sync the fake library")
        assertNull(shown.banner)
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
        advanceTimeBy(11_000) // the switch flow's 5 s grace period, then the loaded-run flow's
        runCurrent()
        // Only now, with both flows stopped and no Room query in flight: a run starts while the screen is stopped.
        db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = START))
        assertFalse(viewModel.mockSwitchEnabled.value, "the stopped screen's 'enabled' is not replayed either")

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
