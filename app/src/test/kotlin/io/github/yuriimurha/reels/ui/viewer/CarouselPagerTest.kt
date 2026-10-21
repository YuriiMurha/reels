package io.github.yuriimurha.reels.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.KotlinSource
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    /** The carousel's pager alone, with its counter over it from the same pager state, as the page shows them. */
    private fun showPager(media: MediaEntity = carousel, player: Player? = null) = compose.setContent {
        ReelsTheme {
            val current by states.collectAsState()
            val pagerState = rememberCarouselPagerState(current)
            Box(Modifier.fillMaxSize()) {
                CarouselPager(media, current, player, onItemSettled = ::onItemSettled, Modifier.fillMaxSize(), pagerState = pagerState)
                CarouselCounter(media, current, pagerState, Modifier.align(Alignment.TopEnd))
            }
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

    /**
     * T1: only a settled page is reported, never the page under a finger that is still down. Reported mid-drag, a video item
     * would start (with its sound) under the finger, and a fling would download the image of every item it passed.
     */
    @Test
    fun aHeldDragPastHalfAnItemReportsNoSettle() {
        showPager()
        compose.onNodeWithTag(CAROUSEL_PAGER_TAG).performTouchInput {
            down(center); moveBy(Offset(-width * 0.2f, 0f)); moveBy(Offset(-width * 0.2f, 0f)); moveBy(Offset(-width * 0.2f, 0f))
        }
        compose.waitForIdle()
        assertEquals(listOf(0), settledReports, "the finger is still down: nothing has settled")
    }

    /** T5: the bright dot is the item shown, and moves with it. */
    @Test
    fun theBrightDotFollowsTheItem() {
        showPosts()
        assertEquals(0, brightDot())

        swipeItemsLeft()
        assertEquals(1, brightDot())

        swipeItemsLeft()
        assertEquals(2, brightDot())
    }

    /** T6: an item whose image is not in yet is the viewer's black, never item 0's cover (that is another picture). */
    @Test
    fun anItemWithoutItsImageIsNotShownAsTheCover() {
        showPager() // the post has a stored cover, and no item has its image yet
        compose.onNodeWithTag(CAROUSEL_COVER_TAG).assertIsDisplayed()

        swipeItemsLeft()

        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("2/3")
        compose.onAllNodesWithTag(CAROUSEL_COVER_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(carouselImageTag(1)).assertCountEquals(0)
    }

    /**
     * T7: every carousel picture, an item's image and the cover alike, is fitted, never cropped: an item's stored size may be 0
     * or mixed, so the viewer never sizes from it (Task 1). Semantics do not say how a picture is scaled, so the source is pinned.
     */
    @Test
    fun everyCarouselPictureIsFitted() {
        val source = File("src/main/kotlin/io/github/yuriimurha/reels/ui/viewer/CarouselPager.kt").readText()
        val pictures = KotlinSource.callArguments(source, "Thumbnail(")
        assertEquals(2, pictures.size, "an item's image and the cover: $pictures")
        for (arguments in pictures) assertTrue(arguments.trim().endsWith("ContentScale.Fit"), "fitted: Thumbnail($arguments)")
    }

    /** The index of the one selected dot. */
    private fun brightDot(): Int {
        val dots = compose.onNodeWithTag(CAROUSEL_DOTS_TAG).onChildren().fetchSemanticsNodes()
        val bright = dots.indices.filter { dots[it].config.getOrNull(SemanticsProperties.Selected) == true }
        assertEquals(1, bright.size, "exactly one bright dot of ${dots.size}: $bright")
        return bright.single()
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

    // --- R26: a video item's poster, until its first frame ---

    /**
     * A player that raises the first-frame event when the test says, as Media3 does when the first frame of the item it plays
     * reaches the surface: `onRenderedFirstFrame`, and `onEvents` with `EVENT_RENDERED_FIRST_FRAME`, which `ContentFrame`'s
     * shutter follows.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    private class FirstFramePlayer(player: Player) : ForwardingPlayer(player) {
        private val listeners = mutableListOf<Player.Listener>()

        override fun addListener(listener: Player.Listener) {
            listeners += listener
            super.addListener(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            listeners -= listener
            super.removeListener(listener)
        }

        fun renderFirstFrame() {
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_RENDERED_FIRST_FRAME).build())
            for (listener in listeners.toList()) {
                listener.onRenderedFirstFrame()
                listener.onEvents(this, events)
            }
        }
    }

    /** The items' pictures as the queue fetched them: an image for item 1, a poster for each video item. */
    private val withPosters = listOf(
        CarouselItemUi(0, MediaType.IMAGE),
        CarouselItemUi(1, MediaType.VIDEO, imagePath = "/carousel/1000001_1.jpg"),
        CarouselItemUi(2, MediaType.VIDEO, imagePath = "/carousel/1000001_2.jpg"),
    )

    private fun displayed(tag: String): Boolean {
        val nodes = compose.onAllNodesWithTag(tag)
        return (0 until nodes.fetchSemanticsNodes().size).any { runCatching { nodes[it].isDisplayed() }.getOrDefault(false) }
    }

    private fun withPlayer(test: (FirstFramePlayer) -> Unit) {
        val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        try {
            test(FirstFramePlayer(exo))
        } finally {
            exo.release()
        }
    }

    /** The settled video item shows its poster over the surface until the player renders its first frame; then the video shows. */
    @Test
    fun aVideoItemsPosterShowsUntilItsFirstFrameThenGoes() = withPlayer { player ->
        state = CarouselUiState(items = withPosters, settled = 1)
        showPager(player = player)
        compose.onNodeWithTag(VIDEO_SURFACE_TAG).assertIsDisplayed()
        assertTrue(displayed(carouselPosterTag(1)), "the poster covers the surface")

        compose.runOnIdle { player.renderFirstFrame() }

        compose.waitForIdle()
        assertTrue(!displayed(carouselPosterTag(1)), "the first frame is there: the poster goes")
        compose.onNodeWithTag(VIDEO_SURFACE_TAG).assertIsDisplayed()
    }

    /**
     * Each video item's poster is its own: when the visit moves on to the next video item, that item shows ITS poster, never
     * the item before's, and the first frame of the item before does not count for it: its poster stays until its own.
     */
    @Test
    fun theNextVideoItemShowsItsOwnPosterUntilItsOwnFirstFrame() = withPlayer { player ->
        state = CarouselUiState(items = withPosters, settled = 1)
        showPager(player = player)
        compose.runOnIdle { player.renderFirstFrame() }
        compose.waitForIdle()
        assertTrue(!displayed(carouselPosterTag(1)) && !displayed(carouselPosterTag(2)), "item 1 plays; no poster shows")

        state = state.copy(settled = 2) // the owner swipes to the next video item; the screen hands it the player

        compose.onNodeWithTag(CAROUSEL_COUNTER_TAG).assertTextEquals("3/3")
        compose.onNodeWithTag(VIDEO_SURFACE_TAG).assertIsDisplayed()
        assertTrue(displayed(carouselPosterTag(2)), "item 2's own poster, until its own first frame")
        assertTrue(!displayed(carouselPosterTag(1)), "never item 1's")

        compose.runOnIdle { player.renderFirstFrame() }
        compose.waitForIdle()
        assertTrue(!displayed(carouselPosterTag(2)), "item 2's first frame: its poster goes")
    }

    /** A video item that cannot play (the screen takes the player away) keeps its poster, under the message that says why. */
    @Test
    fun aVideoItemThatCannotPlayKeepsItsPosterUnderTheMessage() {
        state = CarouselUiState(
            items = withPosters.map { if (it.position == 1) it.copy(failure = "Can't load this item") else it },
            settled = 1,
        )
        showPager(player = null)

        assertTrue(displayed(carouselPosterTag(1)), "the poster stays")
        compose.onNodeWithText("Can't load this item").assertIsDisplayed()
        compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).assertCountEquals(0)
    }

    /**
     * Each poster is its own because only the settled video item gets the player: mid-swipe between two video items, both
     * pages are on screen, and only one draws the surface (two frames on one player would let one item's first frame lower
     * both shutters).
     */
    @Test
    fun midSwipeOnlyTheSettledVideoItemHasASurface() = withPlayer { player ->
        state = CarouselUiState(items = withPosters, settled = 1)
        showPager(player = player)
        compose.waitForIdle()

        compose.onNodeWithTag(CAROUSEL_PAGER_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.2f, 0f))
            moveBy(Offset(-width * 0.2f, 0f))
        }
        compose.waitForIdle()

        assertTrue(displayed(carouselPosterTag(2)), "precondition: item 2 is on screen too")
        compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).assertCountEquals(1)
    }

    /** A video item 0 has no poster of its own: the post's cover covers its surface until the first frame. */
    @Test
    fun aVideoItemZeroShowsTheCoverUntilItsFirstFrame() = withPlayer { player ->
        state = CarouselUiState(items = listOf(CarouselItemUi(0, MediaType.VIDEO), CarouselItemUi(1, MediaType.IMAGE)), settled = 0)
        showPager(player = player)
        compose.onNodeWithTag(VIDEO_SURFACE_TAG).assertIsDisplayed()
        assertTrue(displayed(CAROUSEL_COVER_TAG), "the cover covers the surface")

        compose.runOnIdle { player.renderFirstFrame() }

        compose.waitForIdle()
        assertTrue(!displayed(CAROUSEL_COVER_TAG), "the first frame: the cover goes")
    }

    /** An image item's picture keeps the image tag: only a video item's is a poster. */
    @Test
    fun anImageItemsPictureIsNoPoster() {
        state = CarouselUiState(items = threeItems.map { if (it.position == 1) it.copy(imagePath = "/carousel/1000001_1.jpg") else it }, settled = 1)
        showPager()

        assertTrue(displayed(carouselImageTag(1)))
        compose.onAllNodesWithTag(carouselPosterTag(1)).assertCountEquals(0)
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
