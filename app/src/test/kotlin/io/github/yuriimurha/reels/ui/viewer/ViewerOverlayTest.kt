package io.github.yuriimurha.reels.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The viewer overlay's two buttons are icons (spec 2026-10-12 §3.4): Mute/Unmute and Open on Instagram, each with its words as the
 * content description and as a tooltip on a long press. No text button is left.
 */
@RunWith(AndroidJUnit4::class)
class ViewerOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private var muted by mutableStateOf(false)
    private var toggles = 0
    private var opened: String? = null

    private val reel = mediaEntity("1000001", MediaType.REEL)

    /** A carousel of three items, with item [settled] settled: 0 and 1 are images, 2 is a video. */
    private fun carouselAt(settled: Int) = CarouselUiState(
        items = listOf(CarouselItemUi(0, MediaType.IMAGE), CarouselItemUi(1, MediaType.IMAGE), CarouselItemUi(2, MediaType.VIDEO)),
        settled = settled,
    )

    private fun show(media: MediaEntity = reel, carousel: CarouselUiState? = null) = compose.setContent {
        ReelsTheme {
            ViewerPage(
                media = media,
                player = null,
                collections = emptyList(),
                muted = muted,
                onToggleMute = { toggles++; muted = !muted },
                onTogglePlay = {},
                onOpenInstagram = { opened = it },
                carousel = carousel,
            )
        }
    }

    private fun muteIcons() = compose.onAllNodesWithContentDescription("Mute") // exact match: "Unmute" is another node

    private fun unmuteIcons() = compose.onAllNodesWithContentDescription("Unmute")

    @Test
    fun muteIsAnIconWithItsLabel() {
        muted = true
        show()
        muteIcons().assertCountEquals(0)
        compose.onNodeWithContentDescription("Unmute").assertIsDisplayed().performClick()

        assertEquals(1, toggles)
        compose.onNodeWithContentDescription("Mute").assertIsDisplayed()
        unmuteIcons().assertCountEquals(0)

        compose.onNodeWithContentDescription("Mute").performClick()

        assertEquals(2, toggles)
        compose.onNodeWithContentDescription("Unmute").assertIsDisplayed()
    }

    @Test
    fun openOnInstagramIsAnIcon() {
        show()
        compose.onNodeWithContentDescription("Open on Instagram").assertIsDisplayed().performClick()
        assertEquals("https://www.instagram.com/reel/C1000001/", opened)
    }

    @Test
    fun openOnInstagramUsesThePermalinkOfThePostType() {
        show(mediaEntity("1000002", MediaType.CAROUSEL))
        compose.onNodeWithContentDescription("Open on Instagram").performClick()
        assertEquals("https://www.instagram.com/p/C1000002/", opened)
    }

    @Test
    fun aLongPressShowsTheTooltip() {
        show()
        compose.onAllNodesWithText("Open on Instagram").assertCountEquals(0)
        compose.onAllNodesWithText("Mute").assertCountEquals(0)

        compose.onNodeWithContentDescription("Open on Instagram").performTouchInput { longClick() }
        tooltipText("Open on Instagram").assertIsDisplayed()
        assertNull(opened, "a long press shows the tooltip; it does not open Instagram")

        compose.onNodeWithContentDescription("Mute").performTouchInput { longClick() }
        tooltipText("Mute").assertIsDisplayed()
        assertEquals(0, toggles, "a long press shows the tooltip; it does not toggle the sound")
    }

    /** The tooltip names what the next tap does: it reads "Unmute" while muted. */
    @Test
    fun theMuteTooltipFollowsTheState() {
        muted = true
        show()
        compose.onNodeWithContentDescription("Unmute").performTouchInput { longClick() }
        tooltipText("Unmute").assertIsDisplayed()
    }

    @Test
    fun noTextButtonIsLeft() {
        muted = false
        show()
        val words = hasText("Mute") or hasText("Unmute") or hasText("Open on Instagram")
        compose.onAllNodes(words).assertCountEquals(0)
        compose.onAllNodes(words, useUnmergedTree = true).assertCountEquals(0)

        muted = true
        compose.waitForIdle()
        compose.onAllNodes(words).assertCountEquals(0)
        compose.onNodeWithContentDescription("Unmute").assertIsDisplayed()
    }

    @Test
    fun aReelHasMute() {
        show(reel)
        compose.onNodeWithContentDescription("Mute").assertIsDisplayed()
    }

    @Test
    fun aVideoPostHasMute() {
        show(mediaEntity("1000003", MediaType.VIDEO))
        compose.onNodeWithContentDescription("Mute").assertIsDisplayed()
    }

    @Test
    fun anImageHasNoMuteButOffersInstagram() {
        show(mediaEntity("1000004", MediaType.IMAGE))
        muteIcons().assertCountEquals(0)
        unmuteIcons().assertCountEquals(0)
        compose.onNodeWithContentDescription("Open on Instagram").assertIsDisplayed()
    }

    @Test
    fun aCarouselHasMuteOnlyWhileItsSettledItemIsAVideo() {
        val carousel = mediaEntity("1000005", MediaType.CAROUSEL)
        show(carousel, carouselAt(settled = 1))
        muteIcons().assertCountEquals(0)
        compose.onNodeWithContentDescription("Open on Instagram").assertIsDisplayed()
    }

    @Test
    fun aCarouselOnAVideoItemHasMute() {
        show(mediaEntity("1000005", MediaType.CAROUSEL), carouselAt(settled = 2))
        compose.onNodeWithContentDescription("Mute").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open on Instagram").assertIsDisplayed()
    }

    /** The buttons sit in the overlay, below the dots and far from the "N/M" counter at the top: they cover neither. */
    @Test
    fun theButtonsCoverNeitherTheCounterNorTheDots() {
        show(mediaEntity("1000005", MediaType.CAROUSEL), carouselAt(settled = 2))

        val counter = compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertIsDisplayed().getBoundsInRoot()
        val dots = compose.onNodeWithTag(CAROUSEL_DOTS_TAG).assertIsDisplayed().getBoundsInRoot()
        val mute = compose.onNodeWithContentDescription("Mute").assertIsDisplayed().getBoundsInRoot()
        val open = compose.onNodeWithContentDescription("Open on Instagram").assertIsDisplayed().getBoundsInRoot()

        for ((name, button) in listOf("Mute" to mute, "Open on Instagram" to open)) {
            assertFalse(button.overlaps(counter), "$name $button covers the counter $counter")
            assertFalse(button.overlaps(dots), "$name $button covers the dots $dots")
        }
        assertFalse(mute.overlaps(open), "the two buttons are apart: $mute, $open")
    }

    /**
     * R30: the "N/M" counter is the page's (its overlay layer's) now, no longer drawn inside the carousel's pager, and it sits
     * where it always has: at the page's top end, 16 dp in from the top and from the end.
     */
    @Test
    fun theCounterSitsAtTheTopEnd() {
        show(mediaEntity("1000005", MediaType.CAROUSEL), carouselAt(settled = 0))
        val root = compose.onRoot().getBoundsInRoot()
        val counter = compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertIsDisplayed().getBoundsInRoot()

        assertTrue(abs((root.right - counter.right).value - 16f) <= 0.5f, "the counter $counter at the end of $root")
        assertTrue(abs((counter.top - root.top).value - 16f) <= 0.5f, "the counter $counter at the top of $root")
    }

    /** Open on Instagram sits at the start and Mute at the end, each at the overlay's 16 dp margin, as the text buttons were side by side. */
    @Test
    fun muteIsAtTheEndAndOpenOnInstagramAtTheStart() {
        show()
        val root = compose.onRoot().getBoundsInRoot()
        val mute = compose.onNodeWithContentDescription("Mute").getBoundsInRoot()
        val open = compose.onNodeWithContentDescription("Open on Instagram").getBoundsInRoot()

        // The 40 dp disc sits 4 dp inside its 48 dp touch target, which starts at the 16 dp margin.
        assertTrue(abs((open.left - root.left).value - 20f) <= 0.5f, "Open on Instagram $open at the start of $root")
        assertTrue(abs((root.right - mute.right).value - 20f) <= 0.5f, "Mute $mute at the end of $root")
        assertEquals(open.top, mute.top, "the same row")
    }

    private fun DpRect.overlaps(other: DpRect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** The tooltip is a popup: its text is in a tree of its own, so look in the unmerged tree of every root. */
    private fun tooltipText(text: String) = compose.onNode(hasText(text), useUnmergedTree = true)
}
