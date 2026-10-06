package io.github.yuriimurha.reels.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.cookieValue
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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

    private fun TestScope.repository(requestLog: InMemoryRequestLog = log): SessionRepository {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        val pacer = Pacer(PacingPolicy.Conservative, requestLog, cooldowns, Random(1), now = { testScheduler.currentTime })
        return SessionRepository(cookies, probe, pacer, settings)
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
        repository.loginRequired()
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
        repository.challengeRequired("https://www.instagram.com/challenge/z/")
        assertEquals(SessionState.Challenge("https://www.instagram.com/challenge/z/", "tester"), repository.state.first())
        repository.loginRequired()
        assertEquals(SessionState.Expired("tester"), repository.state.first())
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
