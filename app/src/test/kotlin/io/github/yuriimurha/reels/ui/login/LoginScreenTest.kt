package io.github.yuriimurha.reels.ui.login

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.session.LoginSession
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val session = FakeLoginSession()
    private var done = 0

    private fun findWebView(view: View): WebView? = when {
        view is WebView -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    private fun webView(): WebView = checkNotNull(findWebView(compose.activity.window.decorView)) { "the login screen shows no WebView" }

    /** The real screen over [session], so a test can count the Instagram requests it makes. */
    private fun show(purpose: LoginPurpose, startUrl: String? = null) {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        val viewModel = LoginViewModel(session, purpose)
        compose.setContent {
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    LoginScreen(
                        startUrl = startUrl,
                        onDone = { done++ },
                        onBack = {},
                        purpose = purpose,
                        viewModel = viewModel,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Compose adds the factory's view with a plain addView(view), so a view without LayoutParams gets WRAP_CONTENT.
     * A WebView whose height is WRAP_CONTENT lays the page out at zero height, and Instagram's height:100% containers
     * collapse (only the fixed backdrop paints).
     */
    @Test
    fun theWebViewFillsTheScreenSoThePageIsLaidOutAtFullHeight() {
        session.fingerprint = null
        show(LoginPurpose.LOGIN)
        val webView = findWebView(compose.activity.window.decorView)
        assertNotNull(webView, "the login screen shows no WebView")
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.height)
    }

    @Test
    fun aChallengeScreenOffersCheckAgainWhileItWaits() {
        show(LoginPurpose.CHALLENGE)
        compose.onNodeWithText("Finished verifying on Instagram?").assertIsDisplayed()
        compose.onNodeWithText("Check again").assertIsDisplayed()
        assertEquals(0, session.validations, "opening the challenge page costs no request")
    }

    @Test
    fun checkAgainOnAChallengeScreenValidatesExactlyOnceAndMovesOn() {
        show(LoginPurpose.CHALLENGE)
        compose.onNodeWithText("Check again").performClick()
        // The view model works on the main looper, which only moves while the compose rule idles it.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            session.validations >= 1 && done >= 1
        }
        // The 1 s poll keeps running; the same session must not be sent again.
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(1, session.validations, "one tap is one Instagram request")
        assertEquals(1, done, "a Valid result closes the screen")
    }

    /** Lets the 1 s cookie poll run [seconds] times, the way the real screen does. */
    private fun pollFor(seconds: Int) {
        repeat(seconds) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
    }

    @Test
    fun afterAChallengeResultALoginScreenStopsValidatingAndOffersCheckAgain() {
        session.fingerprint = "s1"
        session.result = { SessionState.Challenge("https://www.instagram.com/challenge/x/", null) }
        show(LoginPurpose.LOGIN)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            session.validations >= 1
        }
        session.fingerprint = "s2" // Instagram re-issues the sessionid during the checkpoint flow
        pollFor(5)
        session.fingerprint = "s3"
        pollFor(5)
        assertEquals(1, session.validations, "a challenged account must not be validated by the poll")
        compose.onNodeWithText("Finished verifying on Instagram?").assertIsDisplayed()
        compose.onNodeWithText("Check again").assertIsDisplayed()
    }

    @Test
    fun aReloginScreenOpensWithoutARequestAndOnTheLoginPage() {
        show(LoginPurpose.RELOGIN) // the jar still holds the expired session
        pollFor(3)
        assertEquals(0, session.validations, "the expired session is known to be dead")
        assertEquals("https://www.instagram.com/accounts/login/", shadowOf(webView()).lastLoadedUrl)
        compose.onAllNodesWithText("Check again").assertCountEquals(0)
    }

    @Test
    fun aReloginScreenChecksTheSessionTheOwnerLogsInWithExactlyOnce() {
        show(LoginPurpose.RELOGIN)
        session.fingerprint = "s2"
        compose.waitUntil(timeoutMillis = 10_000) {
            pollFor(1)
            session.validations >= 1 && done >= 1
        }
        pollFor(5)
        assertEquals(1, session.validations)
        assertEquals(1, done, "a Valid result closes the screen")
    }

    @Test
    fun aLoginScreenOffersNoCheckAgainUntilSomethingHappened() {
        session.fingerprint = null
        show(LoginPurpose.LOGIN)
        compose.onAllNodesWithText("Check again").assertCountEquals(0)
    }

    @Test
    fun aChallengeScreenWithoutAUsableUrlStartsOnInstagramHome() {
        show(LoginPurpose.CHALLENGE, startUrl = null)
        assertEquals("https://www.instagram.com/", shadowOf(webView()).lastLoadedUrl)
    }

    @Test
    fun aChallengeScreenStartsOnItsChallengePage() {
        show(LoginPurpose.CHALLENGE, startUrl = "https://www.instagram.com/challenge/x/")
        assertEquals("https://www.instagram.com/challenge/x/", shadowOf(webView()).lastLoadedUrl)
    }

    @Test
    fun aLoginScreenStartsOnTheLoginPage() {
        session.fingerprint = null
        show(LoginPurpose.LOGIN, startUrl = null)
        assertEquals("https://www.instagram.com/accounts/login/", shadowOf(webView()).lastLoadedUrl)
    }

    private class FakeLoginSession : LoginSession {
        var fingerprint: String? = "s1"
        var validations = 0
        var result: () -> SessionState = { SessionState.Valid("tester") }

        override fun currentSessionFingerprint(): String? = fingerprint

        override fun hasSessionCookies(): Boolean = fingerprint != null

        override fun hasCsrfToken(): Boolean = false

        override suspend fun validate(): SessionState {
            validations++
            return result()
        }
    }
}
