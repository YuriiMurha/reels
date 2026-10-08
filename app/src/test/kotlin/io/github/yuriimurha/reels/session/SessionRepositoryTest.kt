package io.github.yuriimurha.reels.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.cookieValue
import io.github.yuriimurha.reels.sync.RunSession
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerRefusal
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val cookies = RecordingCookieStore()
    private val probe = FakeProbe()
    private val log = InMemoryRequestLog()
    private val cooldowns = InMemoryCooldownStore()

    @After
    fun tearDown() = storeScope.cancel()

    /** The store behind the last [repository], so a test can read what is persisted at a given moment. */
    private lateinit var settings: SettingsStore

    private fun TestScope.repository(
        requestLog: InMemoryRequestLog = log,
        beforeSessionChange: suspend () -> Unit = {},
        beforeCheck: suspend () -> Unit = {},
        debugLog: ((String) -> Unit)? = null,
    ): SessionRepository {
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        val pacer = Pacer(PacingPolicy.Conservative, requestLog, cooldowns, Random(1), now = { testScheduler.currentTime })
        return SessionRepository(cookies, probe, pacer, settings, beforeSessionChange, beforeCheck, debugLog)
    }

    /**
     * What the transport's hooks and the probe did, in order. The cookie jar's own events go into the same list when a test asks
     * ([inJar]), so "before the cookies change" is an order in one list rather than a guess.
     */
    private val order = mutableListOf<String>()

    private val reset: suspend () -> Unit = { order += "reset" }
    private val allow: suspend () -> Unit = { order += "allow" }

    /** A hook that writes into the jar's event list, to see where it falls among the cookie writes. */
    private val inJar: suspend () -> Unit = { cookies.events += "reset" }

    private fun probeRecordsItself() {
        probe.next = { order += "probe"; Account("42", "tester") }
    }

    /** A jar that already holds a session, as after a WebView login. Forgets the seeding writes so tests see only their own. */
    private fun signedIn(sessionId: String = "s1", userId: String = "42") {
        cookies.setCookie(SessionRepository.INSTAGRAM, "sessionid=$sessionId")
        cookies.setCookie(SessionRepository.INSTAGRAM, "ds_user_id=$userId")
        cookies.events.clear()
    }

    @Test
    fun validationIsPacedAndStoresTheHandle() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        assertEquals(SessionState.Valid("tester"), repository.state.first())
        assertEquals(1, log.countSince(-1), "a session check is an Instagram request and goes through the Pacer")
    }

    @Test
    fun loginRequiredKeepsTheLastHandle() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        probe.next = { throw InstagramException.LoginRequired() }
        assertEquals(SessionState.Expired("tester"), repository.validate())
    }

    @Test
    fun challengeKeepsItsUrl() = runTest {
        signedIn()
        val repository = repository()
        probe.next = { throw InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/") }
        assertEquals(SessionState.Challenge("https://www.instagram.com/challenge/x/", null), repository.validate())
    }

    @Test
    fun validateWithoutCookiesIsLoggedOutAndMakesNoRequest() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        val requestsBefore = log.countSince(-1)
        val callsBefore = probe.calls
        cookies.clearAll()
        assertEquals(SessionState.LoggedOut, repository.validate())
        assertEquals(SessionState.LoggedOut, repository.state.first(), "the stale Valid state must not survive")
        assertEquals(callsBefore, probe.calls, "no cookies means no probe")
        assertEquals(requestsBefore, log.countSince(-1), "no cookies means no Pacer request")
    }

    @Test
    fun validateWithOnlyOneCookieIsLoggedOut() = runTest {
        cookies.setCookie(SessionRepository.INSTAGRAM, "sessionid=s1")
        val repository = repository()
        assertEquals(SessionState.LoggedOut, repository.validate())
        assertEquals(0, probe.calls)
        assertEquals(0, log.countSince(-1))
    }

    @Test
    fun loginRequiredSignalWithoutCookiesIsLoggedOut() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        cookies.clearAll()
        repository.loginRequired(repository.epoch())
        assertEquals(SessionState.LoggedOut, repository.state.first())
    }

    @Test
    fun pasteRejectsGarbageWithoutRequest() = runTest {
        val repository = repository()
        assertNull(repository.pasteSessionId("hello there"))
        assertEquals(0, probe.calls)
        assertEquals(0, log.countSince(-1))
        assertFalse(repository.hasSessionCookies())
        assertEquals(emptyList(), cookies.events, "garbage must not touch the cookie jar")
    }

    @Test
    fun pasteWritesBothCookiesThenValidates() = runTest {
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.pasteSessionId(" sessionid=\"42%3Aab\"; "))
        assertEquals("42%3Aab", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(1, probe.calls)
    }

    @Test
    fun pastedCookiesAreScopedSecureAndFlushed() = runTest {
        val repository = repository()
        repository.pasteSessionId("42%3Aab")
        val sessionCookie = cookies.setCookies.single { it.startsWith("sessionid=") }
        val userCookie = cookies.setCookies.single { it.startsWith("ds_user_id=") }
        for (cookie in listOf(sessionCookie, userCookie)) {
            assertTrue("Domain=.instagram.com" in cookie, cookie)
            assertTrue("Path=/" in cookie, cookie)
            assertTrue("Secure" in cookie, cookie)
        }
        assertTrue("HttpOnly" in sessionCookie, sessionCookie)
        assertEquals(2, cookies.setCookies.size, "exactly the two session cookies are written")
        assertTrue(cookies.flushes >= 1, "the jar must be flushed to disk")
        assertEquals("flush", cookies.events.last(), "the flush comes after the last write")
    }

    /** Byte-for-byte: the cookie attributes are what Chromium's jar stores, so a drift here is a silent login bug. */
    @Test
    fun aPasteAndItsRollbackWriteTheExactSetCookieValues() = runTest {
        probe.next = { throw InstagramException.LoginRequired() }
        val repository = repository()
        repository.pasteSessionId("42%3Aab")
        assertEquals(
            listOf(
                "set sessionid=42%3Aab; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=31536000",
                "set ds_user_id=42; Domain=.instagram.com; Path=/; Secure; Max-Age=7776000",
                "flush",
                "set sessionid=; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=0",
                "set ds_user_id=; Domain=.instagram.com; Path=/; Secure; Max-Age=0",
                "flush",
            ),
            cookies.events,
        )
    }

    @Test
    fun pasteDuringCooldownRefusesBeforeTouchingTheJar() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        cookies.events.clear() // a Valid result flushes the jar; only what the refusal itself does is under test below
        val callsBefore = probe.calls
        val until = cooldowns.onRateLimited(testScheduler.currentTime)
        val refusal = assertFailsWith<PacerRefusal.CoolingDown> { repository.pasteSessionId("43%3Acd") }
        assertEquals(until, refusal.until)
        assertEquals("s1", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(emptyList(), cookies.events, "the refusal comes before any cookie write")
        assertEquals(callsBefore, probe.calls)
        assertEquals(SessionState.Valid("tester"), repository.state.first())
    }

    @Test
    fun pasteAtTheDailyBudgetRefusesBeforeTouchingTheJar() = runTest {
        signedIn()
        val full = InMemoryRequestLog(List(PacingPolicy.Conservative.dailyBudget) { 1L })
        val repository = repository(full)
        assertFailsWith<PacerRefusal.DailyBudgetReached> { repository.pasteSessionId("43%3Acd") }
        assertEquals("s1", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals(emptyList(), cookies.events)
        assertEquals(0, probe.calls)
    }

    @Test
    fun pasteThatFailsTransientlyRestoresThePreviousSession() = runTest {
        assertFailedPasteRestores { InstagramException.Transient() }
    }

    @Test
    fun pasteThatIsRateLimitedRestoresThePreviousSession() = runTest {
        assertFailedPasteRestores { InstagramException.RateLimited() }
    }

    @Test
    fun pasteThatMeetsAChangedShapeRestoresThePreviousSession() = runTest {
        assertFailedPasteRestores { InstagramException.ShapeChanged("user") }
    }

    private suspend fun TestScope.assertFailedPasteRestores(failure: () -> InstagramException) {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        probe.next = { throw failure() }
        assertFailsWith<InstagramException> { repository.pasteSessionId("43%3Acd") }
        assertEquals("s1", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(SessionState.Valid("tester"), repository.state.first(), "the stored state is unchanged")
    }

    @Test
    fun pasteThatInstagramRejectsKeepsTheCurrentLogin() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        probe.next = { throw InstagramException.LoginRequired() }
        assertEquals(SessionState.Expired("tester"), repository.pasteSessionId("43%3Acd"))
        assertEquals("s1", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(SessionState.Valid("tester"), repository.state.first(), "a rejected paste leaves the stored state alone")
    }

    @Test
    fun pasteThatNeedsAChallengeKeepsTheCurrentLogin() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        probe.next = { throw InstagramException.ChallengeRequired("https://www.instagram.com/challenge/p/") }
        assertEquals(
            SessionState.Challenge("https://www.instagram.com/challenge/p/", "tester"),
            repository.pasteSessionId("43%3Acd"),
            "the caller still learns why the paste was rejected",
        )
        assertEquals("s1", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(SessionState.Valid("tester"), repository.state.first())
    }

    @Test
    fun rejectedPasteWithNoPreviousSessionLeavesNoSession() = runTest {
        val repository = repository()
        probe.next = { throw InstagramException.LoginRequired() }
        assertEquals(SessionState.Expired(null), repository.pasteSessionId("43%3Acd"))
        assertFalse(repository.hasSessionCookies())
        assertNull(cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertNull(cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(SessionState.LoggedOut, repository.state.first())
    }

    @Test
    fun aRejectedPasteFlushesItsRollback() = runTest {
        signedIn()
        val repository = repository()
        probe.next = { throw InstagramException.LoginRequired() }
        repository.pasteSessionId("43%3Acd")
        assertEquals("flush", cookies.events.last(), "the restored cookies must reach disk too")
        assertTrue(cookies.setCookies.last().startsWith("ds_user_id=42;"), "the previous values are written back last")
    }

    @Test
    fun failedPasteWithNoPreviousSessionLeavesNoSession() = runTest {
        val repository = repository()
        probe.next = { throw InstagramException.Transient() }
        assertFailsWith<InstagramException.Transient> { repository.pasteSessionId("43%3Acd") }
        assertFalse(repository.hasSessionCookies())
        assertNull(cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertNull(cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(SessionState.LoggedOut, repository.state.first())
    }

    @Test
    fun failedPasteKeepsTheOtherCookiesTheJarHolds() = runTest {
        cookies.setCookie(SessionRepository.INSTAGRAM, "csrftoken=c1")
        val repository = repository()
        probe.next = { throw InstagramException.Transient() }
        assertFailsWith<InstagramException.Transient> { repository.pasteSessionId("43%3Acd") }
        assertTrue(repository.hasCsrfToken(), "restoring must not wipe cookies that were never ours")
    }

    @Test
    fun cancelledPasteRestoresThePreviousSession() = runTest {
        signedIn()
        val repository = repository()
        probe.gate = CompletableDeferred()
        val paste = launch { repository.pasteSessionId("43%3Acd") }
        probe.entered.await()
        assertEquals("43%3Acd", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"), "the paste is in flight")
        paste.cancelAndJoin()
        assertEquals("s1", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
    }

    @Test
    fun logoutForgetsTheCookiesAndTheHandle() = runTest {
        val repository = repository()
        repository.pasteSessionId("42%3Aab")
        repository.logout()
        assertFalse(repository.hasSessionCookies())
        assertEquals(SessionState.LoggedOut, repository.state.first())
    }

    // ---- The WebView transport's hooks (R91): what is destroyed, and when ----

    /** Review Focus 3: a page that outlives the cookies could write them back, or keep acting for a session that is gone. */
    @Test
    fun logoutDestroysThePageBeforeClearingCookies() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = inJar)

        repository.logout()

        assertEquals(listOf("reset", "clear"), cookies.events)
    }

    @Test
    fun aPasteDestroysThePageBeforeItWritesTheCookies() = runTest {
        val repository = repository(beforeSessionChange = inJar)

        assertEquals(SessionState.Valid("tester"), repository.pasteSessionId("42%3Aab"))

        assertEquals("reset", cookies.events.first(), "the page goes before the first cookie is written: ${cookies.events}")
        assertEquals(1, cookies.events.count { it == "reset" }, "an accepted paste keeps the page that just checked it: ${cookies.events}")
    }

    @Test
    fun aPasteThePacerRefusesDestroysNothing() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = inJar)
        cooldowns.onRateLimited(testScheduler.currentTime)

        assertFailsWith<PacerRefusal.CoolingDown> { repository.pasteSessionId("43%3Acd") }

        assertEquals(emptyList(), cookies.events, "a refused paste changes nothing, the page included")
    }

    @Test
    fun aRejectedPasteDestroysThePageAgainBeforeItPutsThePreviousCookiesBack() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = inJar)
        probe.next = { throw InstagramException.LoginRequired() }

        assertEquals(SessionState.Expired(null), repository.pasteSessionId("43%3Acd"))

        // The page the rejected id was checked on may be sitting on a login page, and would answer LoginRequired to the
        // restored (valid) session too.
        assertEquals(
            listOf(
                "reset",
                "set sessionid=43%3Acd; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=31536000",
                "set ds_user_id=43; Domain=.instagram.com; Path=/; Secure; Max-Age=7776000",
                "flush",
                "reset",
                "set sessionid=s1; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=31536000",
                "set ds_user_id=42; Domain=.instagram.com; Path=/; Secure; Max-Age=7776000",
                "flush",
            ),
            cookies.events,
        )
    }

    @Test
    fun aCancelledPasteDestroysThePageAgainToo() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = inJar)
        probe.gate = CompletableDeferred()
        val paste = launch { repository.pasteSessionId("43%3Acd") }
        probe.entered.await()

        paste.cancelAndJoin()

        assertEquals(2, cookies.events.count { it == "reset" }, cookies.events.toString())
        assertTrue(
            cookies.events.indexOfLast { it == "reset" } < cookies.events.indexOfLast { it.startsWith("set sessionid=s1") },
            "the page goes before the old cookies come back: ${cookies.events}",
        )
    }

    @Test
    fun aPageThatCannotBeDestroyedNeverStopsALogout() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = { throw IllegalStateException("the WebView is gone") })

        repository.logout()

        assertFalse(repository.hasSessionCookies(), "the owner asked to forget the session")
        assertEquals(SessionState.LoggedOut, repository.state.first())
    }

    /** A reset that fails is swallowed, but a debug build says so: the class of the failure, never its message (it could hold anything). */
    @Test
    fun aPageThatCannotBeDestroyedIsLoggedByClassNameOnly() = runTest {
        signedIn()
        val lines = mutableListOf<String>()
        val repository = repository(
            beforeSessionChange = { throw IllegalStateException("the WebView is gone: secret-detail") },
            debugLog = lines::add,
        )

        repository.logout()

        assertFalse(repository.hasSessionCookies(), "the logout still happened")
        assertEquals(SessionState.LoggedOut, repository.state.first())
        assertEquals(listOf("transport reset failed: IllegalStateException"), lines)
    }

    @Test
    fun aResetThatWorksLogsNothing() = runTest {
        signedIn()
        val lines = mutableListOf<String>()
        val repository = repository(beforeSessionChange = reset, debugLog = lines::add)

        repository.logout()

        assertEquals(listOf("reset"), order)
        assertEquals(emptyList(), lines)
    }

    @Test
    fun aPageThatCannotBeDestroyedNeverStopsAPastesRollback() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = { throw IllegalStateException("the WebView is gone") })
        probe.next = { throw InstagramException.LoginRequired() }

        assertEquals(SessionState.Expired(null), repository.pasteSessionId("43%3Acd"))

        assertEquals("s1", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"), "the previous session is put back")
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
    }

    /**
     * The owner had to act (log in again, finish a challenge) and asks again. The page a login or a challenge landing left
     * behind (the transport remembers it until a reset) would answer the same way for ever, so this check starts on a new one.
     */
    private suspend fun TestScope.assertTheCheckStartsFresh(arrange: suspend (SessionRepository) -> Unit) {
        signedIn()
        probeRecordsItself()
        val repository = repository(beforeSessionChange = reset, beforeCheck = allow)
        arrange(repository)
        order.clear()

        assertEquals(SessionState.Valid("tester"), repository.validate())

        assertEquals(listOf("allow", "reset", "probe"), order)
    }

    @Test
    fun aCheckAfterTheSessionExpiredStartsOnAFreshPage() = runTest {
        assertTheCheckStartsFresh { it.loginRequired(it.epoch()) }
    }

    @Test
    fun aCheckAfterAChallengeStartsOnAFreshPage() = runTest {
        assertTheCheckStartsFresh { it.challengeRequired("https://www.instagram.com/challenge/x/", it.epoch()) }
    }

    @Test
    fun theFirstCheckOfALoginTheOwnerJustMadeStartsOnAFreshPage() = runTest {
        assertTheCheckStartsFresh { } // nothing stored yet (LoggedOut) but the jar holds a session
    }

    @Test
    fun everyRetryAfterAnotherChallengeStartsFresh() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = reset, beforeCheck = allow)
        probe.next = { order += "probe"; throw InstagramException.ChallengeRequired(null) }

        repeat(2) { assertEquals(SessionState.Challenge(null, null), repository.validate()) }

        assertEquals(listOf("allow", "reset", "probe", "allow", "reset", "probe"), order)
    }

    @Test
    fun aCheckOnAValidSessionKeepsItsPage() = runTest {
        signedIn()
        probeRecordsItself()
        val repository = repository(beforeSessionChange = reset, beforeCheck = allow)
        assertEquals(SessionState.Valid("tester"), repository.validate())
        assertEquals(listOf("allow", "reset", "probe"), order, "the first check starts on a LoggedOut state")
        order.clear()

        repeat(2) { assertEquals(SessionState.Valid("tester"), repository.validate()) }

        assertEquals(listOf("allow", "probe", "allow", "probe"), order, "a page that is doing its job is not thrown away")
    }

    @Test
    fun aCheckThePacerRefusesAllowsAndDestroysNothing() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = reset, beforeCheck = allow)
        cooldowns.onRateLimited(testScheduler.currentTime)

        assertFailsWith<PacerRefusal.CoolingDown> { repository.validate() }

        assertEquals(emptyList(), order, "nothing is sent, so nothing needs a page")
    }

    @Test
    fun aCheckWithNoSessionAllowsAndDestroysNothing() = runTest {
        val repository = repository(beforeSessionChange = reset, beforeCheck = allow)

        assertEquals(SessionState.LoggedOut, repository.validate())

        assertEquals(emptyList(), order, "no cookies: no request, so no page")
    }

    @Test
    fun theSessionSignalsOfARunNeverTouchThePage() = runTest {
        signedIn()
        val repository = repository(beforeSessionChange = reset, beforeCheck = allow)
        val epoch = repository.epoch()

        repository.sessionOk("tester", epoch)
        repository.loginRequired(epoch)
        repository.challengeRequired(null, epoch)
        repository.runSession(epoch)

        assertEquals(emptyList(), order)
    }

    /**
     * A reset must never kill another request's call: it runs inside the Pacer's gate, where nothing else is in flight. Real
     * time (the gate is held across a wait), with a clock that always runs ahead so the Pacer's own gaps cost nothing.
     */
    @Test
    fun theFreshPageIsMadeInsideThePacersGateNeverWhileAnotherRequestIsOut() = runBlocking {
        signedIn()
        val ticks = java.util.concurrent.atomic.AtomicLong(0)
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore(), Random(1), now = { ticks.addAndGet(10_000) })
        val repository = SessionRepository(
            cookies, probe, pacer,
            SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "gate2.preferences_pb") }),
            beforeSessionChange = reset, beforeCheck = allow,
        )
        probeRecordsItself()
        repository.loginRequired(repository.epoch()) // Expired: a check would start on a fresh page
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        withTimeout(20_000) {
            val other = async { pacer.interactive { holding.complete(Unit); release.await() } } // some other request is out
            holding.await()
            val check = async { repository.validate() }
            kotlinx.coroutines.delay(300) // far longer than the check needs to reach the gate
            assertEquals(emptyList(), order, "the check waits for the gate before it destroys or allows anything")

            release.complete(Unit)
            other.await()
            assertEquals(SessionState.Valid("tester"), check.await())
        }
        assertEquals(listOf("allow", "reset", "probe"), order)
    }

    /**
     * What a check does about the page depends on the state stored when its turn at the Pacer's gate comes, not on the one stored
     * when `validate()` began: it can wait for the gate (behind another request, with the Pacer's gaps) while a login or an
     * answer lands. [first] makes the state the check starts on, [meanwhile] changes it while another request holds the gate.
     */
    private fun theCheckReadsTheStoredStateInsideTheGate(
        first: suspend (SessionRepository, Int) -> Unit,
        meanwhile: suspend (SessionRepository, Int) -> Unit,
        expectedOrder: List<String>,
    ) = runBlocking {
        signedIn()
        val ticks = java.util.concurrent.atomic.AtomicLong(0)
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore(), Random(1), now = { ticks.addAndGet(10_000) })
        val repository = SessionRepository(
            cookies, probe, pacer,
            SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "gate3.preferences_pb") }),
            beforeSessionChange = reset, beforeCheck = allow,
        )
        probeRecordsItself()
        val epoch = repository.epoch()
        first(repository, epoch)
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        withTimeout(20_000) {
            val other = async { pacer.interactive { holding.complete(Unit); release.await() } } // some other request is out
            holding.await()
            val check = async { repository.validate() }
            kotlinx.coroutines.delay(300) // far longer than the check needs to reach the gate
            assertEquals(emptyList(), order, "the check is still waiting for the gate")

            meanwhile(repository, epoch)
            release.complete(Unit)
            other.await()
            assertEquals(SessionState.Valid("tester"), check.await())
        }
        assertEquals(expectedOrder, order)
    }

    @Test
    fun aCheckResetsWhenTheStateStoppedBeingValidWhileItWaitedForTheGate() {
        theCheckReadsTheStoredStateInsideTheGate(
            first = { repository, epoch -> repository.sessionOk("tester", epoch) }, // Valid when validate() begins
            meanwhile = { repository, epoch -> repository.loginRequired(epoch) }, // Expired when its turn comes
            expectedOrder = listOf("allow", "reset", "probe"),
        )
    }

    @Test
    fun aCheckKeepsItsPageWhenTheStateBecameValidWhileItWaitedForTheGate() {
        theCheckReadsTheStoredStateInsideTheGate(
            first = { repository, epoch -> repository.loginRequired(epoch) }, // Expired when validate() begins
            meanwhile = { repository, epoch -> repository.sessionOk("tester", epoch) }, // Valid when its turn comes
            expectedOrder = listOf("allow", "probe"),
        )
    }

    @Test
    fun aValidationThatFinishesAfterLogoutIsDiscarded() = runTest {
        signedIn()
        val repository = repository()
        val gate = CompletableDeferred<Unit>()
        probe.gate = gate
        val pending = async { repository.validate() }
        probe.entered.await()
        repository.logout()
        gate.complete(Unit)
        assertEquals(SessionState.LoggedOut, pending.await())
        assertEquals(SessionState.LoggedOut, repository.state.first(), "a late probe result must not resurrect the session")
        assertFalse(repository.hasSessionCookies())
    }

    @Test
    fun aValidResultFlushesTheJarSoALoginSurvivesAKill() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(0, cookies.flushes)
        assertEquals(SessionState.Valid("tester"), repository.validate())
        assertEquals(1, cookies.flushes, "Chromium commits cookies lazily: Valid is stored, so the cookies must be flushed with it")
    }

    @Test
    fun aResultThatIsNotValidMakesNoFlush() = runTest {
        signedIn()
        val repository = repository()
        probe.next = { throw InstagramException.LoginRequired() }
        assertEquals(SessionState.Expired(null), repository.validate())
        assertEquals(0, cookies.flushes)
    }

    @Test
    fun aDiscardedValidResultMakesNoFlush() = runTest {
        signedIn()
        val repository = repository()
        val gate = CompletableDeferred<Unit>()
        probe.gate = gate
        val pending = async { repository.validate() }
        probe.entered.await()
        repository.logout()
        gate.complete(Unit)
        assertEquals(SessionState.LoggedOut, pending.await())
        assertEquals(0, cookies.flushes, "a late result for a forgotten session must not touch the jar")
    }

    @Test
    fun aValidationThatFinishesAfterAPasteIsDiscarded() = runTest {
        signedIn()
        val repository = repository()
        val gate = CompletableDeferred<Unit>()
        probe.gate = gate
        val pending = async { repository.validate() }
        probe.entered.await()
        probe.next = { Account("42", "pasted") }
        // Runs up to its first real suspension: the jar already holds the pasted cookies when this returns.
        val paste = async(start = CoroutineStart.UNDISPATCHED) { repository.pasteSessionId("42%3Aab") }
        assertEquals("42%3Aab", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        gate.complete(Unit)
        assertEquals(SessionState.Valid("pasted"), paste.await())
        assertEquals(SessionState.Valid("pasted"), pending.await(), "the old session's result is stale")
        assertEquals(SessionState.Valid("pasted"), repository.state.first())
    }

    @Test
    fun engineSignalsUpdateTheState() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        repository.challengeRequired("https://www.instagram.com/challenge/z/", repository.epoch())
        assertEquals(SessionState.Challenge("https://www.instagram.com/challenge/z/", "tester"), repository.state.first())
        repository.loginRequired(repository.epoch())
        assertEquals(SessionState.Expired("tester"), repository.state.first())
    }

    @Test
    fun staleEpochSignalsAreIgnored() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        val old = repository.epoch()
        repository.logout()
        assertNotEquals(old, repository.epoch(), "a logout starts a new epoch")
        // The owner is logged in again (a new session, in the jar): a run that began under the old one must not touch it.
        signedIn("s2", "43")
        repository.loginRequired(old)
        assertEquals(SessionState.LoggedOut, repository.state.first(), "a stale loginRequired must not expire the new login")
        repository.challengeRequired("https://www.instagram.com/challenge/z/", old)
        assertEquals(SessionState.LoggedOut, repository.state.first(), "a stale challengeRequired must not replace the new login's state")
    }

    @Test
    fun aPasteAndItsRollbackEachStartANewEpoch() = runTest {
        signedIn()
        val repository = repository()
        val before = repository.epoch()
        probe.next = { throw InstagramException.LoginRequired() }
        repository.pasteSessionId("43%3Acd") // rejected and rolled back
        assertTrue(repository.epoch() >= before + 2, "the paste and its rollback both replaced the session")
        repository.loginRequired(before)
        assertEquals(SessionState.LoggedOut, repository.state.first(), "a signal from before the paste is stale")
    }

    @Test
    fun currentEpochSignalsAreApplied() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        repository.loginRequired(repository.epoch())
        assertEquals(SessionState.Expired("tester"), repository.state.first())
    }

    @Test
    fun sessionOkRestoresValidAfterAStaleExpiredBanner() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        // An earlier run (or the lab) left the banner at Expired, but this run's currentUser just succeeded.
        repository.loginRequired(repository.epoch())
        assertEquals(SessionState.Expired("tester"), repository.state.first())
        cookies.events.clear()

        repository.sessionOk("tester", repository.epoch())

        assertEquals(SessionState.Valid("tester"), repository.state.first())
        assertEquals(listOf("flush"), cookies.events, "Valid is stored, so the jar is flushed with it (R45); nothing else is written")
    }

    @Test
    fun sessionOkStoresTheHandleWhenNothingIsStoredYet() = runTest {
        signedIn()
        val repository = repository()
        repository.sessionOk("tester", repository.epoch())
        assertEquals(SessionState.Valid("tester"), repository.state.first())
        assertEquals(1, cookies.flushes)
    }

    @Test
    fun sessionOkWritesNothingWhenTheStateIsAlreadyValidForThatHandle() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        cookies.events.clear()

        repository.sessionOk("tester", repository.epoch())

        assertEquals(SessionState.Valid("tester"), repository.state.first())
        assertEquals(emptyList(), cookies.events, "every successful sync would otherwise flush the jar and rewrite the settings")
    }

    /** What is persisted at the moment the jar is flushed, read from a different thread than the one that is flushing. */
    private fun storedStateAtFlush(into: MutableList<SessionState>) {
        cookies.onFlush = { into += runBlocking { settings.session.first().toState() } }
    }

    /** A kill between the two must not leave "Logged in as" with no sessionid behind it, so the jar goes to disk FIRST. */
    @Test
    fun sessionOkFlushesTheJarBeforeItStoresValid() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        repository.loginRequired(repository.epoch())
        val atFlush = mutableListOf<SessionState>()
        storedStateAtFlush(atFlush)

        repository.sessionOk("tester", repository.epoch())

        assertEquals(listOf<SessionState>(SessionState.Expired("tester")), atFlush, "when the jar was flushed, Valid was not stored yet")
        assertEquals(SessionState.Valid("tester"), repository.state.first())
    }

    @Test
    fun aValidResultFlushesTheJarBeforeItIsStored() = runTest {
        signedIn()
        val repository = repository()
        val atFlush = mutableListOf<SessionState>()
        storedStateAtFlush(atFlush)

        assertEquals(SessionState.Valid("tester"), repository.validate())

        assertEquals(listOf<SessionState>(SessionState.LoggedOut), atFlush, "when the jar was flushed, Valid was not stored yet")
    }

    @Test
    fun sessionOkReplacesAValidStateForAnotherHandle() = runTest {
        signedIn()
        val repository = repository()
        probe.next = { Account("42", "other") }
        assertEquals(SessionState.Valid("other"), repository.validate())

        repository.sessionOk("user_1", repository.epoch())

        assertEquals(SessionState.Valid("user_1"), repository.state.first(), "only an identical Valid is skipped")
    }

    @Test
    fun sessionOkIgnoredAfterLogout() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        val old = repository.epoch()
        repository.logout()
        // A new login is in the jar by the time the old run's answer arrives.
        signedIn("s2", "43")

        repository.sessionOk("tester", old)

        assertEquals(SessionState.LoggedOut, repository.state.first(), "a run that began before the logout cannot log the owner back in")
        assertEquals(emptyList(), cookies.events, "and it must not flush the new session's cookies either")
    }

    @Test
    fun sessionOkIgnoredWithoutSessionCookies() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        repository.loginRequired(repository.epoch())
        // The jar lost its session without going through logout (the WebView cleared it, say): same epoch, no cookies.
        cookies.clearAll()
        cookies.events.clear()

        repository.sessionOk("tester", repository.epoch())

        assertEquals(SessionState.Expired("tester"), repository.state.first())
        assertEquals(emptyList(), cookies.events)
    }

    // ---- R82: the sync run's session gate ----

    @Test
    fun aRunMaySendOnlyUnderAValidSessionOfItsOwnEpoch() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        val epoch = repository.epoch()

        assertEquals(RunSession.USABLE, repository.runSession(epoch))
        assertEquals(RunSession.NOT_USABLE, repository.runSession(epoch - 1), "a run that began under an older session")

        repository.loginRequired(epoch)
        assertEquals(RunSession.NOT_USABLE, repository.runSession(epoch), "expired")

        repository.challengeRequired(CHALLENGE_URL, epoch)
        assertEquals(RunSession.CHALLENGE, repository.runSession(epoch))
        assertEquals(RunSession.CHALLENGE, repository.runSession(epoch - 1), "a stored challenge stops any run as a challenge")
        assertEquals(SessionState.Challenge(CHALLENGE_URL, "tester"), repository.state.first(), "asking changes nothing")

        repository.logout()
        assertEquals(RunSession.NOT_USABLE, repository.runSession(repository.epoch()), "logged out")
    }

    @Test
    fun aPasteMidRunMakesTheRunsSessionUnusableEvenOnceItIsValid() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        val runEpoch = repository.epoch()
        probe.next = { Account("43", "pasted") }

        assertEquals(SessionState.Valid("pasted"), repository.pasteSessionId("43%3Acd"))

        assertEquals(RunSession.NOT_USABLE, repository.runSession(runEpoch), "the run started under the session the paste replaced")
        assertEquals(RunSession.USABLE, repository.runSession(repository.epoch()), "a run started now may send")
    }

    /**
     * The engine asks [SessionRepository.runSession] from INSIDE the Pacer's gate, and a paste holds the session lock while it
     * waits for that gate. If runSession took the lock, each would wait for the other forever. Real time, so the timeout is real.
     */
    @Test
    fun theRunsSessionCheckNeverWaitsForTheLockAPasteHolds() = runBlocking {
        signedIn()
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "gate.preferences_pb") })
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore(), Random(1))
        val repository = SessionRepository(cookies, probe, pacer, settings)
        repository.sessionOk("tester", repository.epoch()) // Valid, with no request: the log stays empty, so nothing waits a gap
        val runEpoch = repository.epoch()
        val holdingGate = CompletableDeferred<Unit>()
        val pasteStarted = CompletableDeferred<Unit>()
        var answer: RunSession? = null
        probe.next = { Account("43", "pasted") }

        withTimeout(10_000) {
            // A sync request holds the Pacer's gate; its precondition goes on once the paste has taken the lock.
            val sync = async {
                runCatching {
                    pacer.sync(pacer.newRun(), precondition = {
                        holdingGate.complete(Unit)
                        pasteStarted.await()
                        answer = repository.runSession(runEpoch)
                        if (answer != RunSession.USABLE) throw IllegalStateException("not usable")
                    }) {}
                }
            }
            holdingGate.await()
            val paste = async(start = CoroutineStart.UNDISPATCHED) { repository.pasteSessionId("43%3Acd") } // takes the lock first
            assertEquals("43%3Acd", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"), "the paste holds the lock")
            pasteStarted.complete(Unit)

            assertTrue(sync.await().isFailure, "the sync request was refused, not sent")
            assertEquals(RunSession.NOT_USABLE, answer)
            assertEquals(SessionState.Valid("pasted"), paste.await(), "and the paste then got the gate")
        }
        assertEquals(1, probe.calls, "the paste's check was the only request")
    }

    @Test
    fun sessionCookiesNeedBothValues() = runTest {
        val repository = repository()
        cookies.setCookie(SessionRepository.INSTAGRAM, "sessionid=s1")
        assertFalse(repository.hasSessionCookies())
        cookies.setCookie(SessionRepository.INSTAGRAM, "ds_user_id=42")
        assertTrue(repository.hasSessionCookies())
    }

    @Test
    fun theFingerprintIsShortStableAndNotTheSessionId() = runTest {
        val repository = repository()
        assertNull(repository.currentSessionFingerprint())
        signedIn()
        val fingerprint = repository.currentSessionFingerprint()
        assertEquals("e8bc163c82ee", fingerprint, "first 12 hex chars of SHA-256 of the sessionid")
        assertEquals(fingerprint, repository.currentSessionFingerprint())
        assertFalse("s1" in fingerprint.orEmpty())
        cookies.setCookie(SessionRepository.INSTAGRAM, "sessionid=s2")
        assertNotEquals(fingerprint, repository.currentSessionFingerprint())
    }

    // ---- H1: a new login starts a new session epoch ----

    /** The owner logs in again in the WebView, as another account: the jar's sessionid changes, then the login screen validates. */
    @Test
    fun aNewWebViewLoginStartsANewEpoch() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        val before = repository.epoch()

        signedIn("s2", "43")
        probe.next = { Account("43", "other") }
        assertEquals(SessionState.Valid("other"), repository.validate())

        assertNotEquals(before, repository.epoch(), "a run that began under the old session must not carry on under the new one")
        assertEquals(RunSession.NOT_USABLE, repository.runSession(before))
        assertEquals(RunSession.USABLE, repository.runSession(repository.epoch()), "a run that starts now may send")
    }

    /** Check now (or the login screen's retry) on the session that is already in the jar must not stop a running sync. */
    @Test
    fun validatingTheSameSessionLeavesTheEpochAlone() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        val before = repository.epoch()

        assertEquals(SessionState.Valid("tester"), repository.validate())
        probe.next = { throw InstagramException.LoginRequired() }
        assertEquals(SessionState.Expired("tester"), repository.validate())

        assertEquals(before, repository.epoch())
        assertEquals(3, probe.calls, "all three checks were real requests")
    }

    /**
     * The epoch is only started when validate() sees a different session. The first validate() of a process has nothing recorded
     * to compare with (nobody has called epoch() yet), so it records the jar's session and starts no epoch. It must be the
     * FIRST call: asking for the epoch first would record the session itself and never reach that branch.
     */
    @Test
    fun theFirstValidateOfAProcessStartsNoEpoch() = runTest {
        signedIn()
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        assertEquals(0, repository.epoch(), "nothing was held under any other session: there is nothing to end")
    }

    /** A process that holds a run from before any check: its epoch was issued for the jar's session as of the run's start. */
    @Test
    fun aRunThatStartedBeforeAnyCheckStopsWhenAnotherAccountLogsIn() = runTest {
        signedIn()
        val repository = repository()
        val runEpoch = repository.epoch()

        signedIn("s2", "43")
        probe.next = { Account("43", "other") }
        assertEquals(SessionState.Valid("other"), repository.validate())

        assertNotEquals(runEpoch, repository.epoch())
    }

    @Test
    fun aRunThatStartedBeforeAnyCheckIsLeftAloneByACheckOfItsOwnSession() = runTest {
        signedIn()
        val repository = repository()
        val runEpoch = repository.epoch()

        assertEquals(SessionState.Valid("tester"), repository.validate())

        assertEquals(runEpoch, repository.epoch())
    }

    /** The epoch starts BEFORE the check's request goes out: that request can wait seconds for the Pacer, and a run resumes meanwhile. */
    @Test
    fun theEpochStartsBeforeTheCheckIsSentNotWhenItsAnswerArrives() = runTest {
        signedIn()
        val repository = repository()
        repository.sessionOk("tester", repository.epoch()) // Valid with no request, so the probe's first call is the check below
        val runEpoch = repository.epoch()
        signedIn("s2", "43")
        val gate = CompletableDeferred<Unit>()
        probe.gate = gate
        probe.next = { Account("43", "other") }

        val pending = async { repository.validate() }
        probe.entered.await()

        assertEquals(RunSession.NOT_USABLE, repository.runSession(runEpoch), "the check is still out, and the old run is already stopped")
        gate.complete(Unit)
        assertEquals(SessionState.Valid("other"), pending.await())
    }

    /** The jar changed while the check was out: its answer belongs to a session that is gone, like one that finishes after a logout. */
    @Test
    fun aCheckWhoseSessionIsReplacedWhileItIsOutIsDiscarded() = runTest {
        signedIn()
        val repository = repository()
        val gate = CompletableDeferred<Unit>()
        probe.gate = gate
        val pending = async { repository.validate() }
        probe.entered.await()
        val during = repository.epoch()

        signedIn("s2", "43") // the WebView login lands while the old session's check is out
        gate.complete(Unit)

        assertEquals(SessionState.LoggedOut, pending.await(), "the stale answer is dropped, the stored state is returned")
        assertEquals(SessionState.LoggedOut, repository.state.first(), "Valid(tester) must not be stored for the new session")
        assertNotEquals(during, repository.epoch())
        assertEquals(0, cookies.flushes, "and a discarded answer never flushes the jar")
    }

    @Test
    fun aPasteThenACheckOfThePastedSessionLeavesTheEpochAlone() = runTest {
        signedIn()
        val repository = repository()
        repository.validate() // an epoch has been issued, for the first session
        probe.next = { Account("43", "pasted") }
        assertEquals(SessionState.Valid("pasted"), repository.pasteSessionId("43%3Acd"))
        val after = repository.epoch()

        assertEquals(SessionState.Valid("pasted"), repository.validate())

        assertEquals(after, repository.epoch(), "the paste already started this epoch, for the pasted session")
    }

    @Test
    fun aRolledBackPasteThenACheckOfTheRestoredSessionLeavesTheEpochAlone() = runTest {
        signedIn()
        val repository = repository()
        repository.validate() // an epoch has been issued, for the first session
        probe.next = { throw InstagramException.LoginRequired() }
        repository.pasteSessionId("43%3Acd") // rejected: the previous cookies are written back under another epoch
        val after = repository.epoch()

        assertEquals(SessionState.Expired("tester"), repository.validate())

        assertEquals(after, repository.epoch(), "the rollback's epoch was issued for the restored session")
    }

    @Test
    fun aLogoutThenACheckWithNoSessionLeavesTheEpochAlone() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        repository.logout()
        val after = repository.epoch()

        assertEquals(SessionState.LoggedOut, repository.validate())

        assertEquals(after, repository.epoch(), "the logout's epoch was issued for 'no session'")
    }

    @Test
    fun aLoginAfterALogoutStartsANewEpoch() = runTest {
        signedIn()
        val repository = repository()
        repository.validate()
        repository.logout()
        val afterLogout = repository.epoch()

        signedIn("s2", "43")
        assertEquals(SessionState.Valid("tester"), repository.validate())

        assertNotEquals(afterLogout, repository.epoch())
    }

    // ---- M1: a jar that cannot be read must not crash the callers of epoch() ----

    @Test
    fun anEpochIsHandedOutEvenWhenTheJarCannotBeRead() = runTest {
        signedIn()
        val repository = repository()
        cookies.readFailure = IllegalStateException("no WebView provider")

        assertEquals(0, repository.epoch(), "epoch() is called outside every try: it must not throw")
    }

    /** Nothing was recorded then, so the next call records the jar as it is by then, and a later login is seen as a change. */
    @Test
    fun theEpochIsRecordedByALaterCallOnceTheJarCanBeRead() = runTest {
        signedIn("s1", "42")
        val repository = repository()
        cookies.readFailure = IllegalStateException("no WebView provider")
        val runEpoch = repository.epoch()
        cookies.readFailure = null
        assertEquals(runEpoch, repository.epoch())

        signedIn("s2", "43")
        probe.next = { Account("43", "other") }
        assertEquals(SessionState.Valid("other"), repository.validate())

        assertNotEquals(runEpoch, repository.epoch(), "the second epoch() recorded the first session, so the login is a change")
    }

    @Test
    fun aCheckOverAJarThatCannotBeReadFailsInsteadOfGuessing() = runTest {
        signedIn("s1", "42")
        val repository = repository()
        repository.epoch()
        cookies.readFailure = IllegalStateException("no WebView provider")

        // Its callers (Check now, the login screen) catch it and say "Couldn't check the session".
        assertFailsWith<IllegalStateException> { repository.validate() }
        assertEquals(0, probe.calls)
    }

    // ---- I1: the gate also compares the jar's account, so a login that no check has seen yet still stops a run ----

    private fun TestScope.validRun(repository: SessionRepository): Int {
        val epoch = repository.epoch()
        runBlocking { repository.sessionOk("tester", epoch) }
        return epoch
    }

    /** The WebView login landed, but nothing has validated yet (the login screen polls once a second, and may be gone). */
    @Test
    fun aRunIsNotUsableOnceTheJarHoldsAnotherAccountEvenBeforeAnyCheck() = runTest {
        signedIn("s1", "42")
        val repository = repository()
        val runEpoch = validRun(repository)
        assertEquals(RunSession.USABLE, repository.runSession(runEpoch))

        signedIn("s2", "43") // another account, no validate()

        assertEquals(RunSession.NOT_USABLE, repository.runSession(runEpoch))
        assertEquals(runEpoch, repository.epoch(), "nothing has ended the epoch: it is the gate that looks at the jar")
        assertEquals(SessionState.Valid("tester"), repository.state.first(), "and the stored state is untouched")
    }

    /** Instagram may re-issue the sessionid for the same account: that is not another login and must not stop a run. */
    @Test
    fun aRotatedSessionIdOfTheSameAccountKeepsTheRunUsable() = runTest {
        signedIn("s1", "42")
        val repository = repository()
        val runEpoch = validRun(repository)

        signedIn("s1-rotated", "42")

        assertEquals(RunSession.USABLE, repository.runSession(runEpoch))
    }

    @Test
    fun aRunIsNotUsableOnceTheJarLostItsAccount() = runTest {
        signedIn("s1", "42")
        val repository = repository()
        val runEpoch = validRun(repository)

        cookies.clearAll() // the WebView dropped its cookies without going through logout

        assertEquals(RunSession.NOT_USABLE, repository.runSession(runEpoch))
    }

    @Test
    fun theAccountOfAPastedSessionIsTheOneARunStartedAfterItIsHeldTo() = runTest {
        signedIn("s1", "42")
        val repository = repository()
        probe.next = { Account("43", "pasted") }
        assertEquals(SessionState.Valid("pasted"), repository.pasteSessionId("43%3Acd"))
        val runEpoch = repository.epoch()

        assertEquals(RunSession.USABLE, repository.runSession(runEpoch))
        signedIn("s3", "44")
        assertEquals(RunSession.NOT_USABLE, repository.runSession(runEpoch))
    }

    private companion object {
        const val CHALLENGE_URL = "https://www.instagram.com/challenge/z/"
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
}
