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
        val viewModel = LoginViewModel(session)
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
        val viewModel = LoginViewModel(session)
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
        val viewModel = LoginViewModel(session)
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

    private class FakeLoginSession : LoginSession {
        var fingerprint: String? = null
        var result: () -> SessionState = { SessionState.Valid("tester") }
        var gate: CompletableDeferred<Unit>? = null
        var validations = 0

        override fun currentSessionFingerprint(): String? = fingerprint

        override fun hasSessionCookies(): Boolean = fingerprint != null

        override suspend fun validate(): SessionState {
            validations++
            gate?.await()
            return result()
        }
    }
}
