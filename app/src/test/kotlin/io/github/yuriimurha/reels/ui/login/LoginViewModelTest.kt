package io.github.yuriimurha.reels.ui.login

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.session.LoginSession
import io.github.yuriimurha.reels.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val session = FakeLoginSession()

    @BeforeTest
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun resetMain() = Dispatchers.resetMain()

    @Test
    fun validatesOnceWhenTheCookiesAppear() = runTest {
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(0, session.validations)

        session.fingerprint = "s1"
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(1, session.validations)
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    @Test
    fun doesNotRevalidateSameSession() = runTest {
        session.fingerprint = "s1"
        session.result = { SessionState.Expired(null) }
        val viewModel = LoginViewModel(session, LoginPurpose.LOGIN)
        repeat(5) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(1, session.validations, "each validation is an Instagram request")
    }

    @Test
    fun aNewSessionIsChecked() = runTest {
        session.fingerprint = "s1"
        session.result = { SessionState.Expired(null) }
        val viewModel = LoginViewModel(session, LoginPurpose.LOGIN)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        session.fingerprint = "s2"
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(2, session.validations)
    }

    @Test
    fun retryChecksTheSameSessionAgain() = runTest {
        session.fingerprint = "s1"
        session.result = { SessionState.Challenge("https://www.instagram.com/challenge/x/", null) }
        val viewModel = LoginViewModel(session, LoginPurpose.LOGIN)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        session.result = { SessionState.Valid("tester") }
        viewModel.retry()
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(2, session.validations)
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    @Test
    fun failuresAreReported() = runTest {
        session.fingerprint = "s1"
        session.result = { throw InstagramException.Transient() }
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertIs<LoginViewModel.Status.Failed>(viewModel.status.value)
    }

    @Test
    fun aFailedCheckIsNotRepeatedUntilTheOwnerAsks() = runTest {
        session.fingerprint = "s1"
        session.result = { throw InstagramException.Transient() }
        val viewModel = LoginViewModel(session)
        repeat(5) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(1, session.validations, "the 1 s poll must not turn a failure into a request loop")
    }

    @Test
    fun aCheckInFlightIsNotStartedTwice() = runTest {
        session.fingerprint = "s1"
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(LoginViewModel.Status.Checking, viewModel.status.value)
        session.fingerprint = "s2" // a different session appears while the first is still being checked
        repeat(3) { viewModel.onCookiesMaybeReady() }
        advanceUntilIdle()
        assertEquals(1, session.validations)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    @Test
    fun unexpectedFailuresNeverShowTheirOwnMessage() = runTest {
        session.fingerprint = "s1"
        session.result = { throw IllegalStateException("an internal detail that must stay internal") }
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(LoginViewModel.Status.Failed("Couldn't check the session"), viewModel.status.value)
    }

    @Test
    fun expectedFailuresShowTheirFixedMessage() = runTest {
        session.fingerprint = "s1"
        session.result = { throw InstagramException.RateLimited() }
        val viewModel = LoginViewModel(session)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(LoginViewModel.Status.Failed("Instagram is limiting requests"), viewModel.status.value)
    }

    @Test
    fun aLoginScreenChecksTheSessionItFindsAtOpenExactlyOnce() = runTest {
        session.fingerprint = "s1"
        val viewModel = LoginViewModel(session, LoginPurpose.LOGIN)
        repeat(3) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(1, session.validations)
    }

    @Test
    fun aChallengeScreenDoesNotCheckTheSessionItWasOpenedWith() = runTest {
        session.fingerprint = "s1" // the session that is waiting on the challenge: already known to be a Challenge
        val viewModel = LoginViewModel(session, LoginPurpose.CHALLENGE)
        repeat(3) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(0, session.validations, "opening the challenge page must not cost an Instagram request")
        assertEquals(LoginViewModel.Status.Waiting, viewModel.status.value)
    }

    @Test
    fun aChallengeScreenChecksAgainWhenTheOwnerAsks() = runTest {
        session.fingerprint = "s1"
        val viewModel = LoginViewModel(session, LoginPurpose.CHALLENGE)
        viewModel.onCookiesMaybeReady()
        viewModel.retry()
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(1, session.validations)
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    @Test
    fun aChallengeScreenChecksAnotherSessionThatAppears() = runTest {
        session.fingerprint = "s1"
        val viewModel = LoginViewModel(session, LoginPurpose.CHALLENGE)
        viewModel.onCookiesMaybeReady()
        session.fingerprint = "s2"
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(1, session.validations)
    }

    @Test
    fun afterAChallengeResultALoginScreenNeverValidatesByItselfAgain() = runTest {
        session.fingerprint = "s1"
        session.result = { SessionState.Challenge("https://www.instagram.com/challenge/x/", null) }
        val viewModel = LoginViewModel(session, LoginPurpose.LOGIN)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(1, session.validations)

        // Instagram re-issues the sessionid during the checkpoint flow: each new one used to cost a request.
        for (fingerprint in listOf("s2", "s3", "s4")) {
            session.fingerprint = fingerprint
            repeat(2) {
                viewModel.onCookiesMaybeReady()
                advanceUntilIdle()
            }
        }
        assertEquals(1, session.validations, "a challenged account must not be polled by the 1 s cookie poll")
        assertIs<LoginViewModel.Status.Done>(viewModel.status.value)
    }

    @Test
    fun afterAChallengeOnlyCheckAgainValidatesAndEachTapIsOneRequest() = runTest {
        session.fingerprint = "s1"
        session.result = { SessionState.Challenge(null, null) }
        val viewModel = LoginViewModel(session, LoginPurpose.LOGIN)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        session.fingerprint = "s2"

        viewModel.retry()
        repeat(3) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(2, session.validations, "one tap on Check again is one request")

        // Still challenged: the screen stops again until the owner taps again.
        session.fingerprint = "s3"
        repeat(3) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(2, session.validations)
        session.result = { SessionState.Valid("tester") }
        viewModel.retry()
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(3, session.validations)
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    @Test
    fun aChallengeScreenAlsoStopsAfterAChallengeResult() = runTest {
        session.fingerprint = "s1"
        session.result = { SessionState.Challenge(null, null) }
        val viewModel = LoginViewModel(session, LoginPurpose.CHALLENGE)
        viewModel.retry()
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(1, session.validations)
        session.fingerprint = "s2"
        repeat(3) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(1, session.validations)
    }

    @Test
    fun aFailedCheckDoesNotStopTheScreenFromCheckingANewSession() = runTest {
        session.fingerprint = "s1"
        session.result = { throw InstagramException.Transient() }
        val viewModel = LoginViewModel(session, LoginPurpose.LOGIN)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        session.fingerprint = "s2"
        session.result = { SessionState.Valid("tester") }
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(2, session.validations, "only a Challenge result stops the automatic checks")
    }

    @Test
    fun aReloginScreenDoesNotCheckTheExpiredSessionItWasOpenedWith() = runTest {
        session.fingerprint = "s1" // the session that just came back Expired: known to be dead
        val viewModel = LoginViewModel(session, LoginPurpose.RELOGIN)
        repeat(3) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(0, session.validations, "opening the login page again must not cost an Instagram request")
        assertEquals(LoginViewModel.Status.Waiting, viewModel.status.value)
    }

    @Test
    fun aReloginScreenChecksTheNewSessionOnceWhenTheOwnerLogsIn() = runTest {
        session.fingerprint = "s1"
        val viewModel = LoginViewModel(session, LoginPurpose.RELOGIN)
        viewModel.onCookiesMaybeReady()
        session.fingerprint = "s2" // the owner logged in: Instagram issued a new sessionid
        repeat(3) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(1, session.validations)
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    @Test
    fun aReloginScreenWithNoSessionInTheJarChecksTheFirstOneThatAppears() = runTest {
        session.fingerprint = null // logged out meanwhile: nothing to seed
        val viewModel = LoginViewModel(session, LoginPurpose.RELOGIN)
        viewModel.onCookiesMaybeReady()
        assertEquals(0, session.validations)
        session.fingerprint = "s1"
        repeat(2) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        assertEquals(1, session.validations)
    }

    @Test
    fun retryWhileACheckIsInFlightDoesNotStartASecondOne() = runTest {
        session.fingerprint = "s1"
        val gate = CompletableDeferred<Unit>()
        session.gate = gate
        val viewModel = LoginViewModel(session, LoginPurpose.LOGIN)
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(LoginViewModel.Status.Checking, viewModel.status.value)
        viewModel.retry()
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(1, session.validations, "a second request while the first is out would double the Instagram traffic")
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(LoginViewModel.Status.Done(SessionState.Valid("tester")), viewModel.status.value)
    }

    /**
     * R106: a screen opened to fix the session (log in again, finish a challenge) closes the hidden instagram.com page when it
     * opens, so the site is not running for that account beside the visible login. Opening still costs no request.
     */
    @Test
    fun aReloginOrChallengeScreenClosesTheHiddenPageWhenItOpens() = runTest {
        for (purpose in listOf(LoginPurpose.RELOGIN, LoginPurpose.CHALLENGE)) {
            val session = FakeLoginSession().apply { fingerprint = "s1" }
            val viewModel = LoginViewModel(session, purpose)
            advanceUntilIdle()
            assertEquals(1, session.pageCloses, "$purpose")
            assertEquals(0, session.validations, "$purpose")

            // Once, when it opens: the screen's polling closes nothing more.
            repeat(3) {
                viewModel.onCookiesMaybeReady()
                advanceUntilIdle()
            }
            assertEquals(1, session.pageCloses, "$purpose")
        }
    }

    /** A first login (no session to fix) and the CSRF screen after a paste leave the hidden page alone. */
    @Test
    fun aLoginOrCsrfScreenLeavesTheHiddenPageAlone() = runTest {
        for (purpose in listOf(LoginPurpose.LOGIN, LoginPurpose.CSRF)) {
            val session = FakeLoginSession().apply { fingerprint = "s1" }
            val viewModel = LoginViewModel(session, purpose)
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
            assertEquals(0, session.pageCloses, "$purpose")
        }
    }

    @Test
    fun aCsrfScreenNeverValidatesAndWaitsForTheToken() = runTest {
        session.fingerprint = "s1"
        val viewModel = LoginViewModel(session, LoginPurpose.CSRF)
        repeat(3) {
            viewModel.onCookiesMaybeReady()
            advanceUntilIdle()
        }
        session.fingerprint = "s2"
        viewModel.onCookiesMaybeReady()
        advanceUntilIdle()
        assertEquals(0, session.validations, "the pasted session was just checked; this screen only fetches a csrftoken")
        assertEquals(LoginViewModel.Status.Waiting, viewModel.status.value)

        session.csrf = true
        viewModel.onCookiesMaybeReady()
        assertEquals(LoginViewModel.Status.CsrfReady, viewModel.status.value)
        assertEquals(0, session.validations)
    }

    @Test
    fun aCsrfScreenGivesUpAfterThirtySecondsWithoutAnError() = runTest {
        session.fingerprint = "s1"
        var clock = 1_000L
        val viewModel = LoginViewModel(session, LoginPurpose.CSRF) { clock }
        clock += 29_999
        viewModel.onCookiesMaybeReady()
        assertEquals(LoginViewModel.Status.Waiting, viewModel.status.value)
        clock += 1
        viewModel.onCookiesMaybeReady()
        assertEquals(LoginViewModel.Status.CsrfReady, viewModel.status.value, "close anyway, nothing alarming")
        assertEquals(0, session.validations)
    }

    private class FakeLoginSession : LoginSession {
        var fingerprint: String? = null
        var csrf = false
        var result: () -> SessionState = { SessionState.Valid("tester") }
        var gate: CompletableDeferred<Unit>? = null
        var validations = 0
        var pageCloses = 0

        override fun currentSessionFingerprint(): String? = fingerprint

        override suspend fun closeHiddenPage() {
            pageCloses++
        }

        override fun hasSessionCookies(): Boolean = fingerprint != null

        override fun hasCsrfToken(): Boolean = csrf

        override suspend fun validate(): SessionState {
            validations++
            gate?.await()
            return result()
        }
    }
}
