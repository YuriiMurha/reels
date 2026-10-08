package io.github.yuriimurha.reels.ui.sync

import androidx.compose.material3.Text
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SyncContentTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(run: SyncRunEntity?) = compose.setContent {
        ReelsTheme {
            SyncContent(
                run = run,
                ui = syncUiState(run, null, 0),
                pacer = null,
                lastSyncAt = null,
                lastFullSyncAt = null,
                onSync = {}, onFullSync = {}, onCancel = {}, onDiscard = {}, onDeleteLibrary = {},
            )
        }
    }

    @Test
    fun freshStateOffersBothModes() {
        show(null)
        compose.onNodeWithText("Sync").assertIsDisplayed()
        compose.onNodeWithText("Full sync").assertIsDisplayed()
    }

    @Test
    fun resumableRunOffersResumeAndDiscardOnly() {
        show(SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.PAUSED, startedAt = 0, lastError = "Cancelled"))
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithText("Discard paused run").assertIsDisplayed()
        compose.onAllNodesWithText("Full sync").assertCountEquals(0)
    }

    /**
     * `run.requestsUsed` is cumulative over every attempt of a run, while the per-run budget restarts on each resume, so
     * showing "420 / 300" mixed two meanings. The line has no denominator; the rolling 24 h budget keeps its own.
     */
    @Test
    fun requestsOfAResumedRunAreShownWithoutAPerRunDenominator() {
        val run = SyncRunEntity(mode = SyncMode.FULL, status = SyncStatus.PAUSED, startedAt = 0, requestsUsed = 420)
        val pacer = PacerStatus(requestsLast24h = 450, dailyBudget = 600, perRunBudget = 300, cooldownUntil = null)
        compose.setContent {
            ReelsTheme {
                SyncContent(
                    run = run,
                    ui = syncUiState(run, pacer, 0),
                    pacer = pacer,
                    lastSyncAt = null,
                    lastFullSyncAt = null,
                    onSync = {}, onFullSync = {}, onCancel = {}, onDiscard = {}, onDeleteLibrary = {},
                )
            }
        }
        // The screen scrolls and these rows are below the fold of the test window, so check existence, not visibility.
        compose.onNodeWithText("Requests (all attempts)").assertExists()
        compose.onNodeWithText("420").assertExists()
        compose.onAllNodesWithText("Requests this run").assertCountEquals(0)
        compose.onAllNodesWithText("420 / 300").assertCountEquals(0)
        compose.onNodeWithText("Requests in 24 h").assertExists()
        compose.onNodeWithText("450 / 600").assertExists()
    }

    // ---- M6: the Storage section's own message line ----

    private fun showWithStorageMessage(message: String?) = compose.setContent {
        ReelsTheme {
            SyncContent(
                run = null,
                ui = syncUiState(null, null, 0),
                pacer = null,
                lastSyncAt = null,
                lastFullSyncAt = null,
                onSync = {}, onFullSync = {}, onCancel = {}, onDiscard = {}, onDeleteLibrary = {},
                storageMessage = message,
                sessionSection = { Text("SESSION-SECTION") },
                developerSection = { Text("DEVELOPER-SECTION") },
            )
        }
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().layoutInfo.coordinates.positionInRoot().y

    @Test
    fun aStorageMessageShowsRightUnderDeleteLibraryNotInTheSessionSection() {
        val message = "Library deleted; some cached files couldn't be removed"
        showWithStorageMessage(message)
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        assertTrue(top("Storage") < top("Delete library"), "the Storage section holds the button")
        assertTrue(top("Delete library") < top(message), "the message is under the button")
        assertTrue(top("SESSION-SECTION") < top("Storage"), "and the session section is somewhere else, above")
        assertTrue(top(message) < top("DEVELOPER-SECTION"), "while the Developer section comes after")
    }

    @Test
    fun withoutAStorageMessageTheSectionIsJustTheButton() {
        showWithStorageMessage(null)
        compose.onNodeWithText("Delete library").assertIsDisplayed()
        compose.onAllNodesWithText("Library deleted", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Couldn't delete", substring = true).assertCountEquals(0)
    }
}
