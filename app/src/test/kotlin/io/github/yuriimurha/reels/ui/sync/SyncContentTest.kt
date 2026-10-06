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
}
