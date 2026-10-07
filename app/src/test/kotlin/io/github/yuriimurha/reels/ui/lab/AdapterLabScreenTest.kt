package io.github.yuriimurha.reels.ui.lab

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.instagram.lab.LabCall
import io.github.yuriimurha.reels.instagram.lab.LabIds
import io.github.yuriimurha.reels.instagram.lab.LabResult
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AdapterLabScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val ran = mutableListOf<LabCall>()

    private val labels = mapOf(
        LabCall.CURRENT_USER to "Who am I",
        LabCall.COLLECTIONS to "Collections",
        LabCall.SAVED_ALL to "All Saved (page 1)",
        LabCall.SAVED_COLLECTION to "First collection (page 1)",
        LabCall.MEDIA_INFO to "Media info (first saved item)",
    )

    private fun show(ui: LabUiState) = compose.setContent {
        ReelsTheme { AdapterLabContent(ui = ui, onRun = { ran += it }) }
    }

    private fun assertEnabledExactly(vararg enabled: LabCall) {
        for ((call, label) in labels) {
            if (call in enabled) compose.onNodeWithText(label).assertIsEnabled() else compose.onNodeWithText(label).assertIsNotEnabled()
        }
    }

    @Test
    fun sayingEveryTapIsOnePacedRequest() {
        show(LabUiState(sessionValid = true))
        compose.onNodeWithText("Each tap sends one paced request to Instagram as the logged-in test account.").assertExists()
    }

    @Test
    fun oneButtonPerCallWithItsLabel() {
        show(LabUiState(sessionValid = true))
        for (label in labels.values) compose.onNodeWithText(label).assertExists()
    }

    @Test
    fun theButtonsAreDisabledWhenTheSessionIsNotValid() {
        // Even with both ids known: without a valid session nothing can be sent.
        show(LabUiState(sessionValid = false, hasCollectionId = true, hasMediaPk = true))
        assertEnabledExactly()
    }

    @Test
    fun aValidSessionEnablesTheThreeIndependentButtonsOnly() {
        show(LabUiState(sessionValid = true))
        assertEnabledExactly(LabCall.CURRENT_USER, LabCall.COLLECTIONS, LabCall.SAVED_ALL)
    }

    @Test
    fun theChainedButtonsComeWithTheirIds() {
        show(LabUiState(sessionValid = true, hasCollectionId = true))
        assertEnabledExactly(LabCall.CURRENT_USER, LabCall.COLLECTIONS, LabCall.SAVED_ALL, LabCall.SAVED_COLLECTION)
    }

    @Test
    fun everyButtonIsDisabledWhileACallIsInFlight() {
        show(LabUiState(sessionValid = true, hasCollectionId = true, hasMediaPk = true, running = LabCall.COLLECTIONS))
        assertEnabledExactly()
    }

    @Test
    fun tappingAButtonAsksForThatCall() {
        show(LabUiState(sessionValid = true, hasCollectionId = true, hasMediaPk = true))
        for ((call, label) in labels) compose.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(LabCall.entries.toList(), ran)
    }

    @Test
    fun noResultShowsNoResultSection() {
        show(LabUiState(sessionValid = true))
        compose.onAllNodesWithText("HTTP", substring = true).assertCountEquals(0)
    }

    @Test
    fun theLatestResultShowsTheCallTheCodeTheClassificationAndTheShape() {
        val shape = "items: array[2]\n  [0]:\n    media:\n      pk: number(19 digits)"
        show(
            LabUiState(
                sessionValid = true,
                shown = LabShown(LabCall.SAVED_ALL, 200, "ok", shape, "/files/lab/saved_all.json"),
            ),
        )
        compose.onNodeWithText("Latest result: All Saved (page 1)").performScrollTo().assertExists()
        compose.onNodeWithText("HTTP 200").assertExists()
        compose.onNodeWithText("Classification: ok").assertExists()
        compose.onNodeWithText(shape).assertExists()
        compose.onNodeWithText("Scrubbed copy: /files/lab/saved_all.json").assertExists()
    }

    @Test
    fun aResultWithoutAScrubbedCopyShowsNoPath() {
        show(LabUiState(sessionValid = true, shown = LabShown(LabCall.CURRENT_USER, 200, "ok", "(not JSON: text/html, 9 chars)", null)))
        compose.onNodeWithText("(not JSON: text/html, 9 chars)").assertExists()
        compose.onAllNodesWithText("Scrubbed copy", substring = true).assertCountEquals(0)
    }

    @Test
    fun aMessageIsShown() {
        show(LabUiState(sessionValid = true, message = "Collections: Cooling down after a rate limit"))
        compose.onNodeWithText("Collections: Cooling down after a rate limit").assertExists()
    }

    @Test
    fun withoutASessionTheScreenSaysToLogInOnTheSyncScreen() {
        show(LabUiState(sessionValid = false))
        compose.onNodeWithText("Log in on the Sync screen to use the lab.").assertExists()
    }

    /** The real screen over the real ViewModel and Pacer; only the runner (the thing that would call Instagram) is fake. */
    @Test
    fun tappingWhoAmIRunsOneCallAndRendersItsShape() {
        val calls = mutableListOf<LabCall>()
        val shape = "id: number(10 digits)\nstatus: ok"
        val runner = object : LabRunner {
            override suspend fun run(call: LabCall, arg: String?): LabResult {
                calls += call
                return LabResult(call, 200, "ok", null, shape, "{}", LabIds(null, null))
            }
        }
        val viewModel = AdapterLabViewModel(
            lab = runner,
            pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore()),
            sessionState = MutableStateFlow(SessionState.Valid("tester")),
            signals = SessionSignals.None,
            labDir = tmp.newFolder("lab"),
            io = Dispatchers.Unconfined,
        )
        compose.setContent { ReelsTheme { AdapterLabScreen(onBack = {}, viewModel = viewModel) } }
        compose.waitForIdle()
        compose.onNodeWithText("Adapter lab").assertExists()

        awaitEnabled("Who am I")
        compose.onNodeWithText("Who am I").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.waitForIdle()
            compose.onAllNodesWithText(shape).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf(LabCall.CURRENT_USER), calls)
        compose.onNodeWithText("HTTP 200").assertExists()
    }

    /** The screen reads the stored session through a flow, so the buttons enable a moment after the first frame. */
    private fun awaitEnabled(label: String) = compose.waitUntil(timeoutMillis = 10_000) {
        compose.waitForIdle()
        runCatching { compose.onNodeWithText(label).assertIsEnabled() }.isSuccess
    }
}
