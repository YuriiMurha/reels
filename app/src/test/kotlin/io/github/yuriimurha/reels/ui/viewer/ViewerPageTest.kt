package io.github.yuriimurha.reels.ui.viewer

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ViewerPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(media: MediaEntity, onOpenInstagram: (String) -> Unit = {}) = compose.setContent {
        ReelsTheme {
            ViewerPage(
                media = media,
                player = null,
                collections = listOf("Workouts"),
                muted = false,
                onToggleMute = {},
                onTogglePlay = {},
                onOpenInstagram = onOpenInstagram,
            )
        }
    }

    @Test
    fun itemWithoutAThumbnailShowsAPlaceholder() {
        show(mediaEntity("m1", MediaType.IMAGE, thumbPath = null))
        compose.onNodeWithText("Not available on Instagram").assertIsDisplayed()
    }

    @Test
    fun openOnInstagramUsesThePermalink() {
        var opened: String? = null
        show(mediaEntity("m1", MediaType.REEL)) { opened = it }
        compose.onNodeWithText("Open on Instagram").performClick()
        assertEquals("https://www.instagram.com/reel/Cm1/", opened)
    }

    @Test
    fun overlayShowsAuthorAndCollections() {
        show(mediaEntity("m1", author = "chef_anna"))
        compose.onNodeWithText("@chef_anna").assertIsDisplayed()
        compose.onNodeWithText("Workouts").assertIsDisplayed()
    }

    @Test
    fun imagesHaveNoMuteButton() {
        show(mediaEntity("m1", MediaType.IMAGE))
        compose.onAllNodesWithText("Mute").assertCountEquals(0)
    }

    @Test
    fun carouselsShowTheCoverWithACountBadge() {
        show(mediaEntity("m1", MediaType.CAROUSEL).copy(carouselCount = 5))
        compose.onNodeWithText("1/5").assertIsDisplayed()
    }

    @Test
    fun carouselWithoutACountStillShowsABadge() {
        show(mediaEntity("m1", MediaType.CAROUSEL))
        compose.onNodeWithText("1/1").assertIsDisplayed()
    }

    @Test
    fun imagesHaveNoCountBadge() {
        show(mediaEntity("m1", MediaType.IMAGE).copy(carouselCount = 5))
        compose.onAllNodesWithText("1/5").assertCountEquals(0)
        compose.onAllNodesWithText("1/1").assertCountEquals(0)
    }
}
