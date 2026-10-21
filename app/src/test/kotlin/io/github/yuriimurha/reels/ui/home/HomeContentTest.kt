package io.github.yuriimurha.reels.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class HomeContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val cards = listOf(
        CollectionCard("__all__", "All Saved", 12, null),
        CollectionCard("uncategorized", "Uncategorized", 3, null),
        CollectionCard("c1", "Workouts", 9, null),
    )

    private var badge by mutableStateOf(SyncBadge.IDLE)

    /** The old top bar's words: the "Saved" title and every label of the status chip. */
    private val oldTopBar = hasText("Saved") or hasText("Not synced") or hasText("Syncing", substring = true) or
        hasText("Synced ", substring = true) or hasText("⚠", substring = true)

    /**
     * The owner's top bar of 2026-10-21: no "Saved" title and no status chip, in any badge state; the search bar says
     * "Search" and is a button; the sync button has no caption, only its content description.
     */
    @Test
    fun theTopBarHasNoTitleAndNoStatusChip() {
        compose.setContent {
            ReelsTheme { HomeContent(cards, badge, onOpenCard = {}, onOpenSearch = {}, onOpenSync = {}) }
        }
        for (state in SyncBadge.entries) {
            badge = state
            compose.waitForIdle()
            compose.onAllNodes(oldTopBar).assertCountEquals(0)
            compose.onAllNodes(oldTopBar, useUnmergedTree = true).assertCountEquals(0)
            compose.onNodeWithTag(HOME_SEARCH_TAG).assertIsDisplayed().assertTextEquals("Search").assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            compose.onNodeWithTag(SYNC_BUTTON_TAG).assertIsDisplayed().assertContentDescriptionEquals(state.label)
            compose.onAllNodes(hasText("Sync", substring = true)).assertCountEquals(0)
        }
    }

    /** Like Instagram's search tab: the search bar fills the width from the start, and the sync button sits at the end. */
    @Test
    fun theSearchBarFillsTheWidthAndTheSyncButtonIsAtTheEnd() {
        compose.setContent {
            ReelsTheme { HomeContent(cards, badge, onOpenCard = {}, onOpenSearch = {}, onOpenSync = {}) }
        }
        val root = compose.onRoot().getBoundsInRoot()
        val search = compose.onNodeWithTag(HOME_SEARCH_TAG).getBoundsInRoot()
        val sync = compose.onNodeWithTag(SYNC_BUTTON_TAG).getBoundsInRoot()
        assertTrue(search.left <= 16.dp, "the search bar starts at the start margin: $search")
        assertTrue(sync.right >= root.right - 8.dp, "the sync button is at the end: $sync in $root")
        assertTrue(search.right <= sync.left, "the search bar ends before the sync button: $search, $sync")
        assertTrue(sync.left - search.right <= 16.dp, "and runs up to it: $search, $sync")
        assertTrue(search.right - search.left >= (root.right - root.left) * 0.6f, "the search bar fills the width: $search in $root")
    }

    @Test
    fun theSearchBarOpensSearchAndTheSyncButtonOpensSync() {
        var searches = 0
        var syncs = 0
        compose.setContent {
            ReelsTheme { HomeContent(cards, badge, onOpenCard = {}, onOpenSearch = { searches++ }, onOpenSync = { syncs++ }) }
        }
        compose.onNodeWithTag(HOME_SEARCH_TAG).performClick()
        assertEquals(1 to 0, searches to syncs)
        compose.onNodeWithTag(SYNC_BUTTON_TAG).performClick()
        assertEquals(1 to 1, searches to syncs)
    }

    @Test
    fun cardsRenderInTheGivenOrder() {
        compose.setContent {
            ReelsTheme { HomeContent(cards, SyncBadge.IDLE, onOpenCard = {}, onOpenSearch = {}, onOpenSync = {}) }
        }
        val names = compose.onAllNodesWithTag("collection-name").fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
        assertEquals(listOf("All Saved", "Uncategorized", "Workouts"), names)
    }

    @Test
    fun tappingACardOpensIt() {
        var opened: CollectionCard? = null
        compose.setContent {
            ReelsTheme { HomeContent(cards, SyncBadge.IDLE, onOpenCard = { opened = it }, onOpenSearch = {}, onOpenSync = {}) }
        }
        compose.onNodeWithText("Workouts").performClick()
        assertEquals("c1", opened?.id)
    }

    @Test
    fun emptyLibraryPointsToSync() {
        var syncOpened = false
        compose.setContent {
            ReelsTheme {
                HomeContent(emptyList(), SyncBadge.IDLE, onOpenCard = {}, onOpenSearch = {}, onOpenSync = { syncOpened = true })
            }
        }
        compose.onNodeWithText("Nothing synced yet").assertIsDisplayed()
        compose.onNodeWithText("Open Sync").performClick()
        assertEquals(true, syncOpened)
    }
}
