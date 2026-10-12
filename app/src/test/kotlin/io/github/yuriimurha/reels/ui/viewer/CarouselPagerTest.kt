package io.github.yuriimurha.reels.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * A carousel post in the viewer (spec 2026-10-12 §3.2), with the fake library's shape: items at positions 0, 1 are images,
 * 2 a video. Swiping left and right moves between the items, up and down between the posts.
 */
@RunWith(AndroidJUnit4::class)
class CarouselPagerTest {
    @get:Rule
    val compose = createComposeRule()

    private val carousel = mediaEntity("1000001", MediaType.CAROUSEL, author = "creator_1").copy(carouselCount = 3)
    private val reel = mediaEntity("1000002", MediaType.REEL, author = "creator_2")
    private val threeItems = listOf(
        CarouselItemUi(0, MediaType.IMAGE),
        CarouselItemUi(1, MediaType.IMAGE),
        CarouselItemUi(2, MediaType.VIDEO),
    )

    /**
     * What the ViewModel holds for the settled carousel, as the screen gets it: a StateFlow, collected into Compose state, so an
     * item report comes back a hop later, as in the app. [onItemSettled] moves its settled item, as the ViewModel does.
     */
    private val states = MutableStateFlow(CarouselUiState(items = threeItems))
    private var state: CarouselUiState
        get() = states.value
        set(value) {
            states.value = value
        }
    private val settledReports = mutableListOf<Int>()
    private var opened: String? = null

    private fun onItemSettled(position: Int) {
        settledReports += position
        states.update { it.copy(settled = position) }
    }

    /** The viewer's two-level paging: posts up and down ([posts]), and the carousel's own pager inside its page. */
    private fun showPosts(posts: List<MediaEntity> = listOf(carousel, reel)) = compose.setContent {
        ReelsTheme {
            val current by states.collectAsState()
            val pager = rememberPagerState { posts.size }
            VerticalPager(pager, Modifier.fillMaxSize()) { page ->
                val media = posts[page]
                ViewerPage(
                    media = media,
                    player = null,
                    collections = emptyList(),
                    muted = false,
                    onToggleMute = {},
                    onTogglePlay = {},
                    onOpenInstagram = { opened = it },
                    carousel = current.takeIf { media.type == MediaType.CAROUSEL },
                    onItemSettled = ::onItemSettled,
                )
            }
        }
    }

    private fun showPager(media: MediaEntity = carousel, player: Player? = null) = compose.setContent {
        ReelsTheme {
            val current by states.collectAsState()
            CarouselPager(media, current, player, onItemSettled = ::onItemSettled, Modifier.fillMaxSize())
        }
    }

    private fun swipeItemsLeft() = compose.onNodeWithTag(CAROUSEL_PAGER_TAG).performTouchInput { swipeLeft() }

    @Test
    fun swipingLeftShowsTheNextItemAndTheCounter() {
        showPosts()
        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/3")

        swipeItemsLeft()

        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("2/3")
        compose.onNodeWithText("@creator_1").assertIsDisplayed() // the post did not change
        compose.onAllNodesWithText("@creator_2").assertCountEquals(0)
        assertEquals(1, state.settled, "the pager reported the item it settled on")
        assertEquals(listOf(0, 1), settledReports)
    }

    @Test
    fun aVerticalSwipeOnACarouselChangesThePost() {
        showPosts()
        compose.onNodeWithText("@creator_1").assertIsDisplayed()

        compose.onNodeWithTag(CAROUSEL_PAGER_TAG).performTouchInput { swipeUp() }

        compose.onNodeWithText("@creator_2").assertIsDisplayed()
        compose.onAllNodesWithText("@creator_1").assertCountEquals(0)
        assertEquals(listOf(0), settledReports, "no item was swiped")
    }

    @Test
    fun theFirstItemShowsTheCoverAtOnce() {
        showPager()
        compose.onNodeWithTag(CAROUSEL_COVER_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(carouselImageTag(0)).assertCountEquals(0)
    }

    /** While the items are being filled (one paced request), the cover shows, with a small loading mark and "1/N" from the post. */
    @Test
    fun whileTheItemsLoadTheCoverShowsWithALoadingMark() {
        state = CarouselUiState(loading = true)
        showPager(carousel.copy(carouselCount = 5))

        compose.onNodeWithTag(CAROUSEL_COVER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CAROUSEL_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/5")

        state = CarouselUiState(items = threeItems)
        compose.onAllNodesWithTag(CAROUSEL_LOADING_TAG).assertCountEquals(0)
        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/3")
    }

    @Test
    fun aLoadedImageTakesThePlaceOfThePlaceholder() {
        state = CarouselUiState(items = threeItems.map { if (it.position == 1) it.copy(imagePath = "/carousel/1000001-1.jpg") else it })
        showPager()

        swipeItemsLeft()

        compose.onNodeWithTag(carouselImageTag(1)).assertIsDisplayed()
    }

    @Test
    fun anItemThatCannotLoadSaysSo() {
        state = CarouselUiState(items = threeItems.map { if (it.position == 1) it.copy(failure = "Can't load this item") else it })
        showPosts()
        compose.onAllNodesWithText("Can't load this item").assertCountEquals(0)

        swipeItemsLeft()

        compose.onNodeWithText("Can't load this item").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open on Instagram").assertIsDisplayed().performClick()
        assertEquals("https://www.instagram.com/p/C1000001/", opened)
    }

    @Test
    fun coverOnlyShowsOneOfN() {
        state = CarouselUiState(coverOnlyMessage = "Can't load this item")
        showPager(carousel.copy(carouselCount = 4))

        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/4")
        compose.onNodeWithText("Can't load this item").assertIsDisplayed()
        compose.onNodeWithTag(CAROUSEL_COVER_TAG).assertIsDisplayed()

        swipeItemsLeft()
        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/4") // nothing to swipe to
    }

    /** Task 1's note: M comes from the stored items when there are any, the post's own count only when there are none. */
    @Test
    fun theCounterCountsTheStoredItems() {
        showPager(carousel.copy(carouselCount = 7))
        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/3")
    }

    @Test
    fun aCarouselWithNoCountAndNoItemsStillReadsOneOfOne() {
        state = CarouselUiState(coverOnlyMessage = "Can't load this item")
        showPager(carousel.copy(carouselCount = null))
        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/1")
    }

    /** The dots sit with the overlay, and only when there are items to move between. */
    @Test
    fun theDotsShowOnlyWhenThereAreItems() {
        showPosts()
        compose.onNodeWithTag(CAROUSEL_DOTS_TAG).assertIsDisplayed()

        state = CarouselUiState(coverOnlyMessage = "Can't load this item")
        compose.onAllNodesWithTag(CAROUSEL_DOTS_TAG).assertCountEquals(0)
    }

    /** A post shown again is a new visit: its state starts at item 0 again, and the pager follows it there. */
    @Test
    fun thePagerFollowsANewVisitBackToItemZero() {
        showPosts()
        swipeItemsLeft()
        swipeItemsLeft()
        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("3/3")

        state = CarouselUiState(items = threeItems) // what a new visit starts with

        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/3")
        compose.onNodeWithTag(CAROUSEL_COVER_TAG).assertIsDisplayed()
    }

    /** The mute control belongs to video: on a carousel it shows while the settled item is a video (an icon). */
    @Test
    fun theMuteControlShowsOnlyOnAVideoItem() {
        showPosts()
        compose.onAllNodesWithContentDescription("Mute").assertCountEquals(0)

        swipeItemsLeft()
        swipeItemsLeft()

        compose.onNodeWithContentDescription("Mute").assertIsDisplayed()
    }

    @Test
    fun aCarouselPageWithoutItsStateShowsTheCoverAndOneOfN() {
        compose.setContent {
            ReelsTheme {
                ViewerPage(carousel.copy(carouselCount = 6), null, emptyList(), false, {}, {}, {}, carousel = null)
            }
        }
        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("1/6")
        compose.onNodeWithTag(CAROUSEL_COVER_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(CAROUSEL_LOADING_TAG).assertCountEquals(0)
    }

    /**
     * A pager made again (a rotation, or the page composed anew) starts on the visit's item. It must not report item 0 first:
     * the ViewModel would flip to 0 and back, and the screen stop and re-resolve that item.
     */
    @Test
    fun aPagerMadeAgainStartsOnTheVisitsItemWithoutReportingItemZero() {
        state = CarouselUiState(items = threeItems, settled = 2)
        showPager()

        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("3/3")
        assertEquals(listOf(2), settledReports)
        assertEquals(2, state.settled)
    }

    /** The per-page gate: even with a player, only the settled VIDEO item draws it; an image item never does. */
    @Test
    fun onlyTheSettledVideoItemDrawsThePlayer() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        try {
            state = CarouselUiState(items = threeItems, settled = 1)
            showPager(player = player)
            compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("2/3")
            compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).assertCountEquals(0)

            state = state.copy(settled = 2) // the visit moves to the video item; the pager follows

            compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("3/3")
            compose.onNodeWithTag(VIDEO_SURFACE_TAG).assertIsDisplayed()
        } finally {
            player.release()
        }
    }

    @Test
    fun aPostThatIsNotACarouselHasNoCarouselParts() {
        compose.setContent {
            ReelsTheme { ViewerPage(reel, null, emptyList(), false, {}, {}, {}, carousel = CarouselUiState(items = threeItems)) }
        }
        compose.onAllNodesWithTag(CAROUSEL_PAGER_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(CAROUSEL_COUNTER_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(CAROUSEL_DOTS_TAG).assertCountEquals(0)
    }
}
