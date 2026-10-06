package io.github.yuriimurha.reels.ui.home

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.ui.common.SyncStatusSummary
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class HomeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val cards = listOf(
        CollectionCard("__all__", "All Saved", 12, null),
        CollectionCard("uncategorized", "Uncategorized", 3, null),
        CollectionCard("c1", "Workouts", 9, null),
    )

    @Test
    fun cardsRenderInTheGivenOrder() {
        compose.setContent {
            ReelsTheme { HomeContent(cards, SyncStatusSummary.Never, onOpenCard = {}, onOpenSearch = {}, onOpenSync = {}) }
        }
        val names = compose.onAllNodesWithTag("collection-name").fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
        assertEquals(listOf("All Saved", "Uncategorized", "Workouts"), names)
    }

    @Test
    fun tappingACardOpensIt() {
        var opened: CollectionCard? = null
        compose.setContent {
            ReelsTheme { HomeContent(cards, SyncStatusSummary.Never, onOpenCard = { opened = it }, onOpenSearch = {}, onOpenSync = {}) }
        }
        compose.onNodeWithText("Workouts").performClick()
        assertEquals("c1", opened?.id)
    }

    @Test
    fun emptyLibraryPointsToSync() {
        var syncOpened = false
        compose.setContent {
            ReelsTheme {
                HomeContent(emptyList(), SyncStatusSummary.Never, onOpenCard = {}, onOpenSearch = {}, onOpenSync = { syncOpened = true })
            }
        }
        compose.onNodeWithText("Nothing synced yet").assertIsDisplayed()
        compose.onNodeWithText("Open Sync").performClick()
        assertEquals(true, syncOpened)
    }
}
