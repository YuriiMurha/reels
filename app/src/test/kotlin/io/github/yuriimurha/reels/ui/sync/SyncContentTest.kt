package io.github.yuriimurha.reels.ui.sync

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
}
