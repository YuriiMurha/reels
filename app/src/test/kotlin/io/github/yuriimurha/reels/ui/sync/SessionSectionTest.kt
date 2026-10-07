package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SessionSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private var resolved: String? = "unset"
    private var loggedOut = 0
    private var loggedIn = 0
    private var reloggedIn = 0

    private fun show(state: SessionState?) = compose.setContent {
        ReelsTheme {
            SessionSection(
                state = state,
                message = null,
                onLogin = { loggedIn++ },
                onRelogin = { reloggedIn++ },
                onResolveChallenge = { resolved = it },
                onLogout = { loggedOut++ },
                onCheck = {},
                onPaste = {},
            )
        }
    }

    private val allButtons = listOf("Log in", "Log in again", "Resolve on Instagram", "Check now", "Paste sessionid", "Log out")

    /** Exactly [present] are shown (all of them fully on screen: none truncated or pushed out of a Row) and no other button exists. */
    private fun assertButtons(vararg present: String) {
        for (label in allButtons) {
            if (label in present) compose.onNodeWithText(label).assertIsDisplayed() else compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun loggedOutOffersExactlyLoginAndPaste() {
        show(SessionState.LoggedOut)
        assertButtons("Log in", "Paste sessionid")
    }

    @Test
    fun logInOpensAPlainLoginAndLogInAgainOpensARelogin() {
        show(SessionState.LoggedOut)
        compose.onNodeWithText("Log in").performClick()
        assertEquals(1 to 0, loggedIn to reloggedIn)
    }

    @Test
    fun validOffersExactlyCheckNowAndLogout() {
        show(SessionState.Valid("tester"))
        assertButtons("Check now", "Log out")
        compose.onNodeWithText("Log out").performClick()
        assertEquals(1, loggedOut)
    }

    @Test
    fun anExpiredSessionCanBeLoggedOutOrReplacedByAPaste() {
        show(SessionState.Expired("tester"))
        assertButtons("Log in again", "Paste sessionid", "Log out")
        compose.onNodeWithText("Log out").performClick()
        assertEquals(1, loggedOut)
        compose.onNodeWithText("Log in again").performClick()
        assertEquals(0 to 1, loggedIn to reloggedIn, "Log in again is a RELOGIN, not a plain login")
    }

    @Test
    fun aChallengedSessionCanBeLoggedOutOrReplacedByAPaste() {
        show(SessionState.Challenge("https://www.instagram.com/challenge/x/", "tester"))
        assertButtons("Resolve on Instagram", "Check now", "Paste sessionid", "Log out")
        compose.onNodeWithText("Log out").performClick()
        assertEquals(1, loggedOut)
    }

    @Test
    fun whileTheStoredStateIsLoadingThereAreNoButtons() {
        show(null)
        compose.onNodeWithText("Instagram session").assertIsDisplayed()
        compose.onNodeWithText("Checking session\u2026").assertIsDisplayed()
        assertButtons()
    }

    @Test
    fun loggedOutOffersLoginAndPaste() {
        show(SessionState.LoggedOut)
        compose.onNodeWithText("Log in").assertIsDisplayed()
        compose.onNodeWithText("Paste sessionid").assertIsDisplayed()
    }

    @Test
    fun validShowsTheHandleAndLogout() {
        show(SessionState.Valid("tester"))
        compose.onNodeWithText("Logged in as @tester").assertIsDisplayed()
        compose.onNodeWithText("Log out").assertIsDisplayed()
    }

    @Test
    fun challengeOpensItsUrl() {
        show(SessionState.Challenge("https://www.instagram.com/challenge/x/", "tester"))
        compose.onNodeWithText("Resolve on Instagram").performClick()
        assertEquals("https://www.instagram.com/challenge/x/", resolved)
    }

    private fun showPasteDialog(error: String? = null, onSubmit: (String) -> Unit = {}, onDismiss: () -> Unit = {}) =
        compose.setContent { ReelsTheme { PasteSessionDialog(error = error, onSubmit = onSubmit, onDismiss = onDismiss) } }

    private val passwordField = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    @Test
    fun theExpiredAndChallengeMessagesAreShown() {
        compose.setContent {
            ReelsTheme {
                SessionSection(
                    state = SessionState.Expired("tester"),
                    message = "Temporary network or server problem",
                    onLogin = {}, onRelogin = {}, onResolveChallenge = {}, onLogout = {}, onCheck = {}, onPaste = {},
                )
            }
        }
        compose.onNodeWithText("Session expired (@tester)").assertIsDisplayed()
        compose.onNodeWithText("Log in again").assertIsDisplayed()
        compose.onNodeWithText("Temporary network or server problem").assertIsDisplayed()
    }

    @Test
    fun useItWaitsForSomethingToBeTypedAndPassesItOn() {
        var submitted: String? = null
        showPasteDialog(onSubmit = { submitted = it })
        compose.onNodeWithText("Use it").assertIsNotEnabled()
        compose.onNode(passwordField).performTextInput("42%3Aab")
        compose.onNodeWithText("Use it").assertIsEnabled().performClick()
        assertEquals("42%3Aab", submitted)
    }

    @Test
    fun whatIsTypedIsMaskedOnScreen() {
        showPasteDialog()
        compose.onNode(passwordField).performTextInput("42%3Aab")
        val shown = compose.onNode(passwordField).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertEquals("\u2022".repeat(7), shown, "what the field shows must be bullets, never the sessionid")
    }

    @Test
    fun aPasteErrorIsShownInTheDialog() {
        showPasteDialog(error = "That doesn't look like a sessionid")
        compose.onNodeWithText("That doesn't look like a sessionid").assertIsDisplayed()
    }

    @Test
    fun cancelDismisses() {
        var dismissed = false
        showPasteDialog(onDismiss = { dismissed = true })
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(true, dismissed)
    }
}
