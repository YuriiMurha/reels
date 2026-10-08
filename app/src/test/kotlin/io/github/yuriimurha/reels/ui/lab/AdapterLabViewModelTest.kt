package io.github.yuriimurha.reels.ui.lab

import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.session.RecordingCookieStore
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.session.toStored
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.lab.LabCall
import io.github.yuriimurha.reels.instagram.lab.LabIds
import io.github.yuriimurha.reels.instagram.lab.LabResult
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.sync.RunSession
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lab ViewModel over a real [Pacer] (virtual time, in-memory log and cooldown) and fakes for everything that would
 * reach Instagram: no HTTP, no WebView, no real file outside the temporary folder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdapterLabViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val log = InMemoryRequestLog()
    private val cooldowns = InMemoryCooldownStore()
    private val runner = FakeRunner()
    private val signals = FakeSignals()
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Valid("tester"))
    private lateinit var labDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        labDir = File(tmp.root, "lab")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The clock is the test scheduler's, so the Pacer's 2 s interactive gap passes in virtual time. */
    private fun TestScope.newPacer() = Pacer(PacingPolicy.Conservative, log, cooldowns, now = { START + testScheduler.currentTime })

    private fun TestScope.viewModel(
        dir: File = labDir,
        signals: SessionSignals = this@AdapterLabViewModelTest.signals,
        pacer: Pacer = newPacer(),
    ): AdapterLabViewModel {
        val viewModel = AdapterLabViewModel(
            lab = runner,
            pacer = pacer,
            sessionState = sessionState,
            signals = signals,
            labDir = dir,
            io = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { viewModel.ui.collect {} }
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun eachTapIsOnePacedRequest() = runTest {
        val viewModel = viewModel()
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        val firstTapEndedAt = testScheduler.currentTime
        viewModel.tap(LabCall.COLLECTIONS)
        advanceUntilIdle()

        assertEquals(2, log.countSince(0), "every tap is exactly one request through the Pacer")
        assertEquals(listOf(LabCall.CURRENT_USER, LabCall.COLLECTIONS), runner.calls.map { it.first })
        assertTrue(
            testScheduler.currentTime - firstTapEndedAt >= PacingPolicy.Conservative.interactiveMinGapMs,
            "the second tap waited out the interactive lane's minimum gap",
        )
        assertNull(viewModel.ui.value.running)
    }

    @Test
    fun rateLimitArmsTheCooldownAndKeepsTheShape() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ ->
            labResult(call, httpCode = 429, classification = "RateLimited", error = InstagramException.RateLimited(), shape = RATE_LIMIT_SHAPE)
        }
        viewModel.tap(LabCall.COLLECTIONS)
        advanceUntilIdle()

        assertNotNull(cooldowns.activeUntil(), "a RateLimited answer must arm the persisted cooldown")
        val shown = assertNotNull(viewModel.ui.value.shown, "the shape of the 429 is still shown")
        assertEquals(LabCall.COLLECTIONS, shown.call)
        assertEquals(429, shown.httpCode)
        assertEquals("RateLimited", shown.classification)
        assertEquals(RATE_LIMIT_SHAPE, shown.shape)
        assertEquals("Collections: Instagram is limiting requests", viewModel.ui.value.message)
        assertNull(viewModel.ui.value.running)

        // From now on the Pacer refuses: no second request, and the result of the 429 stays on screen.
        viewModel.tap(LabCall.SAVED_ALL)
        advanceUntilIdle()
        assertEquals(1, runner.calls.size)
        assertEquals("All Saved (page 1): Cooling down after a rate limit", viewModel.ui.value.message)
        assertEquals(RATE_LIMIT_SHAPE, viewModel.ui.value.shown?.shape)
    }

    @Test
    fun anOrdinaryFailureAnswerDoesNotArmTheCooldown() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ ->
            labResult(call, httpCode = 404, classification = "ShapeChanged", error = InstagramException.ShapeChanged("http.404"))
        }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertNull(cooldowns.activeUntil())
        assertEquals("ShapeChanged", viewModel.ui.value.shown?.classification)
        assertEquals("Who am I: Unexpected Instagram response at http.404", viewModel.ui.value.message)
    }

    @Test
    fun refusalSendsNothing() = runTest {
        cooldowns.onRateLimited(START)
        val viewModel = viewModel()
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertEquals(emptyList(), runner.calls, "a refused tap must not reach the runner")
        assertEquals(0, log.countSince(0), "a refused tap is not a logged request")
        assertEquals("Who am I: Cooling down after a rate limit", viewModel.ui.value.message)
        assertNull(viewModel.ui.value.shown)
        assertNull(viewModel.ui.value.running, "the buttons come back after a refusal")
    }

    @Test
    fun writesOnlyScrubbedJson() = runTest {
        val viewModel = viewModel()
        val scrubbed = "{\n  \"items\": [\n    {\n      \"pk\": \"1000000001\"\n    }\n  ]\n}"
        runner.next = { call, _ ->
            labResult(call, shape = SHAPE_MARKER, scrubbedJson = scrubbed, ids = LabIds(REAL_COLLECTION_ID, REAL_PK))
        }
        viewModel.tap(LabCall.SAVED_ALL)
        advanceUntilIdle()

        val file = File(labDir, "saved_all.json")
        assertEquals(scrubbed, file.readText())
        assertEquals(listOf("saved_all.json"), labDir.list()!!.toList())
        assertEquals(file.path, viewModel.ui.value.shown?.savedPath)
        assertNothingElseWasWritten(listOf(file))
        assertFalse(viewModel.ui.value.toString().contains(REAL_PK), "an id must not be in the screen's state")
        assertFalse(viewModel.ui.value.toString().contains(REAL_COLLECTION_ID), "an id must not be in the screen's state")

        // A later call of the same button overwrites its file.
        val second = "{\n  \"items\": []\n}"
        runner.next = { call, _ -> labResult(call, scrubbedJson = second) }
        viewModel.tap(LabCall.SAVED_ALL)
        advanceUntilIdle()
        assertEquals(second, file.readText())
        assertEquals(listOf("saved_all.json"), labDir.list()!!.toList())
    }

    @Test
    fun everyCallHasItsOwnFileNamedAfterIt() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ -> labResult(call, scrubbedJson = "{}", ids = LabIds(REAL_COLLECTION_ID, REAL_PK)) }
        for (call in LabCall.entries) {
            viewModel.tap(call)
            advanceUntilIdle()
        }
        assertEquals(
            listOf("collections.json", "current_user.json", "media_info.json", "saved_all.json", "saved_collection.json"),
            labDir.list()!!.sorted(),
        )
    }

    @Test
    fun aResultWithNoScrubbedCopyWritesNothing() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ -> labResult(call, httpCode = 200, shape = "(not JSON: text/html, 120 chars)", scrubbedJson = null) }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertFalse(labDir.exists() && labDir.list()!!.isNotEmpty(), "nothing to save, nothing saved")
        assertEquals("(not JSON: text/html, 120 chars)", viewModel.ui.value.shown?.shape)
        assertNull(viewModel.ui.value.shown?.savedPath)
    }

    /** An export after the run must never hand back an answer older than the one the screen shows. */
    @Test
    fun anAnswerWithNoScrubbedCopyRemovesThatCallsOldFileAndOnlyThat() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ -> labResult(call, scrubbedJson = "{\"call\": \"${call.name}\"}") }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        viewModel.tap(LabCall.COLLECTIONS)
        advanceUntilIdle()
        assertEquals(listOf("collections.json", "current_user.json"), labDir.list()!!.sorted())

        // The same button again, now answered with something that is not JSON.
        runner.next = { call, _ -> labResult(call, shape = "(not JSON: text/html, 120 chars)", scrubbedJson = null) }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertEquals(listOf("collections.json"), labDir.list()!!.sorted(), "only the call that was just answered loses its file")
        assertEquals("{\"call\": \"COLLECTIONS\"}", File(labDir, "collections.json").readText())
        assertEquals("(not JSON: text/html, 120 chars)", viewModel.ui.value.shown?.shape)
        assertNull(viewModel.ui.value.shown?.savedPath, "no file, so no path on screen")
        assertNull(viewModel.ui.value.message, "removing a stale copy is not a failure")
    }

    /** No answer at all (a refusal, a network error) says nothing about the file, so a refused tap keeps the last one. */
    @Test
    fun aTapThatGetsNoAnswerLeavesTheEarlierFileAlone() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ -> labResult(call, scrubbedJson = "{}") }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        runner.next = { _, _ -> throw InstagramException.Transient() }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertEquals(listOf("current_user.json"), labDir.list()!!.toList())
    }

    @Test
    fun aWriteFailureIsSaidWithoutItsPathOrText() = runTest {
        val blocker = File(tmp.root, "not-a-folder").apply { writeText("x") }
        val viewModel = viewModel(dir = blocker)
        runner.next = { call, _ -> labResult(call, scrubbedJson = "{}") }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertEquals("Who am I: Couldn't save the scrubbed copy", viewModel.ui.value.message)
        assertNull(viewModel.ui.value.shown?.savedPath)
        assertNotNull(viewModel.ui.value.shown, "the shape is still shown")
    }

    @Test
    fun chainedButtonsWaitForIds() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ ->
            when (call) {
                LabCall.COLLECTIONS -> labResult(call, ids = LabIds(REAL_COLLECTION_ID, null))
                LabCall.SAVED_ALL -> labResult(call, ids = LabIds(null, REAL_PK))
                else -> labResult(call)
            }
        }
        val before = viewModel.ui.value
        assertTrue(before.canRun(LabCall.CURRENT_USER))
        assertTrue(before.canRun(LabCall.COLLECTIONS))
        assertTrue(before.canRun(LabCall.SAVED_ALL))
        assertFalse(before.canRun(LabCall.SAVED_COLLECTION), "needs a collection id first")
        assertFalse(before.canRun(LabCall.MEDIA_INFO), "needs a saved item's pk first")

        // A tap on a chained button that is not enabled yet sends nothing.
        viewModel.tap(LabCall.SAVED_COLLECTION)
        viewModel.tap(LabCall.MEDIA_INFO)
        advanceUntilIdle()
        assertEquals(emptyList(), runner.calls)
        assertEquals(0, log.countSince(0))

        viewModel.tap(LabCall.COLLECTIONS)
        advanceUntilIdle()
        assertTrue(viewModel.ui.value.canRun(LabCall.SAVED_COLLECTION))
        assertFalse(viewModel.ui.value.canRun(LabCall.MEDIA_INFO), "All Saved has not returned a pk yet")

        viewModel.tap(LabCall.SAVED_COLLECTION)
        advanceUntilIdle()
        assertEquals(LabCall.SAVED_COLLECTION to REAL_COLLECTION_ID, runner.calls.last(), "the chained call gets the id")

        viewModel.tap(LabCall.SAVED_ALL)
        advanceUntilIdle()
        assertTrue(viewModel.ui.value.canRun(LabCall.MEDIA_INFO))
        viewModel.tap(LabCall.MEDIA_INFO)
        advanceUntilIdle()
        assertEquals(LabCall.MEDIA_INFO to REAL_PK, runner.calls.last(), "the chained call gets the pk")
    }

    @Test
    fun aTapWhileACallIsInFlightIsIgnored() = runTest {
        val viewModel = viewModel()
        val gate = CompletableDeferred<Unit>()
        runner.gate = gate
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        assertEquals(LabCall.CURRENT_USER, viewModel.ui.value.running)
        assertTrue(LabCall.entries.none { viewModel.ui.value.canRun(it) }, "every button is disabled while a call is out")

        viewModel.tap(LabCall.COLLECTIONS)
        viewModel.tap(LabCall.CURRENT_USER)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, runner.calls.size, "a double tap is one request")
        assertEquals(1, log.countSince(0))
        assertNull(viewModel.ui.value.running)
    }

    /**
     * Two taps in the same main-thread frame, before the first coroutine has run at all: the guard must already be up when
     * `tap` returns, not only once the Pacer block is entered.
     */
    @Test
    fun aSynchronousDoubleTapIsOneRequest() = runTest {
        val viewModel = viewModel()
        viewModel.tap(LabCall.CURRENT_USER)
        viewModel.tap(LabCall.CURRENT_USER) // no advance in between
        advanceUntilIdle()

        assertEquals(1, runner.calls.size, "a double tap is one request")
        assertEquals(1, log.countSince(0))
        assertNull(viewModel.ui.value.running)
    }

    @Test
    fun aTapWhileTheSessionIsNotValidSendsNothing() = runTest {
        val viewModel = viewModel()
        sessionState.value = SessionState.Expired("tester")
        advanceUntilIdle()
        assertFalse(viewModel.ui.value.canRun(LabCall.CURRENT_USER))

        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        assertEquals(emptyList(), runner.calls)
        assertEquals(0, log.countSince(0))
        assertNull(viewModel.ui.value.running)
    }

    /**
     * R85: a tap that passed the session check can still wait seconds for the Pacer (the 2 s gap here), and a challenge can be
     * stored meanwhile (by the viewer, say). The lab asks again from inside the gate, as the video resolver does (R79).
     */
    @Test
    fun aChallengeStoredWhileTheTapWaitsForThePacerSendsNothing() = runTest {
        val viewModel = viewModel()
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        viewModel.tap(LabCall.COLLECTIONS)
        runCurrent() // passed its first check; now inside the Pacer, waiting out the 2 s gap after the first tap
        assertEquals(LabCall.COLLECTIONS, viewModel.ui.value.running, "precondition: the second tap is queued")

        sessionState.value = SessionState.Challenge(null, "tester")
        advanceUntilIdle()

        assertEquals(listOf(LabCall.CURRENT_USER), runner.calls.map { it.first }, "the queued call never reached Instagram")
        assertEquals(1, log.countSince(0), "and was not logged: it used no budget")
        assertNull(viewModel.ui.value.running, "the buttons come back")
        assertNull(viewModel.ui.value.message, "no failure to report: the screen already says the session needs attention")
        assertEquals(emptyList(), signals.events, "and nothing was signalled")
    }

    /** I1: the epoch or the account behind the session changed while the tap waited (a login as another account, a paste). */
    @Test
    fun aSessionThatIsNoLongerTheOneTheTapStartedUnderSendsNothing() = runTest {
        val viewModel = viewModel()
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        viewModel.tap(LabCall.COLLECTIONS)
        runCurrent()
        assertEquals(LabCall.COLLECTIONS, viewModel.ui.value.running, "precondition: the second tap is queued")

        signals.usable = RunSession.NOT_USABLE // the stored state still says Valid
        advanceUntilIdle()

        assertEquals(listOf(LabCall.CURRENT_USER), runner.calls.map { it.first }, "the queued call never reached Instagram")
        assertEquals(1, log.countSince(0), "and used no budget")
        assertEquals(listOf(3, 3), signals.asked, "asked from inside the gate by each tap, with the epoch the tap started under")
        assertNull(viewModel.ui.value.message)
    }

    /**
     * The in-gate check must ask about the epoch the tap STARTED under, not the epoch current when the gate finally opens. Here
     * the epoch moves on (a logout, a paste, a login as another account) while an interactive request of the owner's holds the
     * gate and the tap waits behind it. The fake answers like the session layer: only the epoch it still holds is usable. Asking
     * with a fresh `signals.epoch()` from inside the gate would be told "usable" and send a request under the wrong session.
     */
    @Test
    fun theInGateCheckAsksWithTheEpochTheTapStartedUnderNotTheOneCurrentWhenTheGateOpens() = runTest {
        val pacer = newPacer()
        val viewModel = viewModel(pacer = pacer)
        val release = CompletableDeferred<Unit>()
        launch { pacer.interactive { release.await() } } // a slow request of the owner's holds the gate
        runCurrent()
        assertEquals(1, log.countSince(0), "precondition: the holder is in flight")

        viewModel.tap(LabCall.CURRENT_USER)
        runCurrent()
        assertEquals(LabCall.CURRENT_USER, viewModel.ui.value.running, "precondition: the tap passed its first check and is queued")
        assertEquals(emptyList(), signals.asked, "precondition: the in-gate check has not run, the tap is still waiting for the gate")

        signals.current = 4 // the session the tap started under (epoch 3) is replaced while it waits
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(3), signals.asked, "asked once, from inside the gate, with the epoch the tap started under")
        assertEquals(emptyList(), runner.calls, "the old epoch is no longer usable, so the queued call never reached Instagram")
        assertEquals(1, log.countSince(0), "and was not logged: only the holder's request was")
        assertNull(viewModel.ui.value.running, "the buttons come back")
        assertNull(viewModel.ui.value.message)
        assertEquals(emptyList(), signals.events, "and nothing was signalled")
    }

    /**
     * M1: with the real session layer over a jar that cannot be read (no WebView provider), `epoch()` used to throw in the tap's
     * coroutine, outside every try: the debug screen crashed the app. Now the tap sends nothing and shows a failure.
     */
    @Test
    fun aJarThatCannotBeReadDoesNotCrashTheLabAndSendsNothing() = runTest {
        val jar = RecordingCookieStore().apply { readFailure = IllegalStateException("no WebView provider") }
        val storeScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
            settings.setSession(SessionState.Valid("tester").toStored())
            val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore())
            val repository = SessionRepository(jar, object : SessionProbe { override suspend fun currentUser() = error("never asked") }, pacer, settings)
            val viewModel = viewModel(signals = repository)

            viewModel.tap(LabCall.CURRENT_USER)
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { while (viewModel.ui.value.running != null || viewModel.ui.value.message == null) { delay(10) } }
            }

            assertEquals(emptyList(), runner.calls, "nothing was sent")
            assertEquals(0, log.countSince(0))
            assertEquals("Who am I: The call failed", viewModel.ui.value.message)
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun aTapThatPassesTheInGateCheckIsSent() = runTest {
        val viewModel = viewModel()
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        viewModel.tap(LabCall.COLLECTIONS)
        runCurrent()
        sessionState.value = SessionState.Valid("tester2") // still a valid session: the call goes out
        advanceUntilIdle()

        assertEquals(listOf(LabCall.CURRENT_USER, LabCall.COLLECTIONS), runner.calls.map { it.first })
        assertEquals(2, log.countSince(0))
    }

    @Test
    fun theButtonsAreDisabledUntilTheStoredSessionHasBeenRead() = runTest {
        val pacer = Pacer(PacingPolicy.Conservative, log, cooldowns, now = { START + testScheduler.currentTime })
        val viewModel = AdapterLabViewModel(
            lab = runner,
            pacer = pacer,
            sessionState = MutableSharedFlow(), // the stored state has not been read: nothing emitted yet
            signals = signals,
            labDir = labDir,
            io = StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch { viewModel.ui.collect {} }
        advanceUntilIdle()
        assertTrue(LabCall.entries.none { viewModel.ui.value.canRun(it) })

        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        assertEquals(emptyList(), runner.calls)
    }

    @Test
    fun aChallengeAnswerIsSignalledToTheSessionAndItsUrlIsNeverShown() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ ->
            labResult(call, httpCode = 302, classification = "ChallengeRequired", error = InstagramException.ChallengeRequired(CHALLENGE_URL))
        }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertEquals(listOf("challenge:$CHALLENGE_URL@3"), signals.events)
        assertEquals("Who am I: Instagram requires verification", viewModel.ui.value.message)
        assertEquals("ChallengeRequired", viewModel.ui.value.shown?.classification)
        assertFalse(viewModel.ui.value.toString().contains("challenge/"), "the challenge URL must never reach the screen's state")
        assertNull(cooldowns.activeUntil())
    }

    @Test
    fun aLoginRequiredAnswerIsSignalledToTheSession() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ ->
            labResult(call, httpCode = 403, classification = "LoginRequired", error = InstagramException.LoginRequired())
        }
        viewModel.tap(LabCall.COLLECTIONS)
        advanceUntilIdle()

        assertEquals(listOf("login@3"), signals.events)
        assertEquals("Collections: Instagram session is not logged in", viewModel.ui.value.message)
    }

    @Test
    fun theSignalCarriesTheEpochReadBeforeTheCallNotTheOneAfterIt() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ ->
            signals.current = 4 // the owner logs out, or pastes another session, while the request is in flight
            labResult(call, httpCode = 403, classification = "LoginRequired", error = InstagramException.LoginRequired())
        }
        viewModel.tap(LabCall.COLLECTIONS)
        advanceUntilIdle()

        assertEquals(listOf("login@3"), signals.events, "a signal for a session that is gone must carry that session's epoch, so it is ignored")
    }

    @Test
    fun aLoginRequiredThrownBeforeAnyRequestIsSignalledToo() = runTest {
        val viewModel = viewModel()
        runner.next = { _, _ -> throw InstagramException.LoginRequired() } // e.g. no ds_user_id cookie
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertEquals(listOf("login@3"), signals.events)
        assertEquals("Who am I: Instagram session is not logged in", viewModel.ui.value.message)
        assertNull(viewModel.ui.value.running)
    }

    @Test
    fun aNetworkFailureShowsItsFixedMessageAndKeepsThePreviousResult() = runTest {
        val viewModel = viewModel()
        runner.next = { call, _ -> labResult(call, shape = SHAPE_MARKER) }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        runner.next = { _, _ -> throw InstagramException.Transient() }
        viewModel.tap(LabCall.COLLECTIONS)
        advanceUntilIdle()

        assertEquals("Collections: Temporary network or server problem", viewModel.ui.value.message)
        assertEquals(SHAPE_MARKER, viewModel.ui.value.shown?.shape)
        assertEquals(emptyList(), signals.events)
        assertNull(cooldowns.activeUntil())
    }

    @Test
    fun anUnexpectedFailureNeverShowsItsOwnText() = runTest {
        val viewModel = viewModel()
        runner.next = { _, _ -> throw IllegalStateException("an internal detail: /data/user/0/app/files/secret") }
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()

        assertEquals("Who am I: The call failed", viewModel.ui.value.message)
        assertNull(viewModel.ui.value.running)
    }

    @Test
    fun theNextTapClearsTheMessage() = runTest {
        cooldowns.onRateLimited(START)
        val viewModel = viewModel()
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        assertNotNull(viewModel.ui.value.message)

        testScheduler.advanceTimeBy(2 * 3_600_000L) // past the 1 h cooldown
        viewModel.tap(LabCall.CURRENT_USER)
        advanceUntilIdle()
        assertNull(viewModel.ui.value.message)
        assertEquals(LabCall.CURRENT_USER, viewModel.ui.value.shown?.call)
    }

    /** Neither the ids nor the shape may be in any file the lab wrote, not just in the one it named. */
    private fun assertNothingElseWasWritten(expected: List<File>) {
        val written = tmp.root.walkTopDown().filter { it.isFile }.toList()
        assertEquals(expected.map { it.path }.sorted(), written.map { it.path }.sorted())
        for (file in written) {
            val text = file.readText()
            for (secret in listOf(REAL_PK, REAL_COLLECTION_ID, SHAPE_MARKER)) assertFalse(text.contains(secret), "${file.name} holds $secret")
        }
    }

    private fun labResult(
        call: LabCall,
        httpCode: Int = 200,
        classification: String = "ok",
        error: InstagramException? = null,
        shape: String = "shape of ${call.name}",
        scrubbedJson: String? = null,
        ids: LabIds = LabIds(null, null),
    ) = LabResult(call, httpCode, classification, error, shape, scrubbedJson, ids)

    private class FakeRunner : LabRunner {
        val calls = mutableListOf<Pair<LabCall, String?>>()
        var next: (LabCall, String?) -> LabResult = { call, _ -> LabResult(call, 200, "ok", null, "shape", null, LabIds(null, null)) }

        /** When set, calls wait for it after being recorded. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun run(call: LabCall, arg: String?): LabResult {
            calls += call to arg
            gate?.await()
            return next(call, arg)
        }
    }

    /** Records each signal with the epoch it carried ("login@3"). [current] is what the session layer's epoch is right now. */
    private class FakeSignals : SessionSignals {
        val events = mutableListOf<String>()

        var current = 3

        /** What the in-gate check answers for the current epoch, and the epochs it was asked with. */
        var usable = RunSession.USABLE
        val asked = mutableListOf<Int>()

        /** Like the session layer: work that started under any epoch but the current one is not usable. */
        override suspend fun runSession(epoch: Int): RunSession {
            asked += epoch
            return if (epoch == current) usable else RunSession.NOT_USABLE
        }

        override fun epoch(): Int = current

        override suspend fun sessionOk(username: String, epoch: Int) {
            events += "ok:$username@$epoch"
        }

        override suspend fun loginRequired(epoch: Int) {
            events += "login@$epoch"
        }

        override suspend fun challengeRequired(challengeUrl: String?, epoch: Int) {
            events += "challenge:$challengeUrl@$epoch"
        }
    }

    private companion object {
        const val START = 10_000_000L
        const val REAL_PK = "3100000000000000001"
        const val REAL_COLLECTION_ID = "17900000000000001"
        const val SHAPE_MARKER = "SHAPE-MARKER-LINE"
        const val RATE_LIMIT_SHAPE = "message: string(len 31, text)\nstatus: string(len 4, text)"
        const val CHALLENGE_URL = "https://example.invalid/challenge/x/"
    }
}
