package io.github.yuriimurha.reels.ui.viewer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CarouselItemEntity
import io.github.yuriimurha.reels.data.db.CollectionMediaEntity
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * The real viewer over the Mock-mode container (debug build, fake backend, the bundled clip): a carousel post with stored
 * items (image, image, video), then a reel. The settled item is the unit: the counter follows the swipe, the video item gets
 * the player only while it is the settled item of the settled post.
 */
@RunWith(AndroidJUnit4::class)
class ViewerScreenCarouselTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val carousel = mediaEntity("1000001", MediaType.CAROUSEL, author = "creator_1").copy(carouselCount = 3)
    private val reel = mediaEntity("1000002", MediaType.REEL, author = "creator_2")

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
        assertTrue(container.usesFake, "these tests run on the Mock-mode container")
        runBlocking {
            container.db.mediaDao().upsert(listOf(carousel, reel))
            container.db.collectionDao().upsertMemberships(
                listOf(
                    CollectionMediaEntity(ALL_SAVED_ID, carousel.pk, sortKey = 2, lastSeenRunId = 1),
                    CollectionMediaEntity(ALL_SAVED_ID, reel.pk, sortKey = 1, lastSeenRunId = 1),
                ),
            )
            container.db.carouselItemDao().replace(
                carousel.pk,
                List(3) { position ->
                    val video = position == 2
                    CarouselItemEntity(
                        carousel.pk, position, if (video) MediaType.VIDEO else MediaType.IMAGE, 1080, 1350,
                        "fake://thumb/${carousel.pk}-$position", if (video) "fake://video/${carousel.pk}-$position" else null, null,
                    )
                },
            )
        }
        compose.setContent {
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ViewerScreen(MediaSource.Collection(ALL_SAVED_ID), startIndex = 0, onIndexSettled = {}, onBack = {})
                }
            }
        }
    }

    @After
    fun tearDown() {
        container.db.close()
    }

    /** Room, Paging and the visit's fill run off the main thread: poll until [condition] holds. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        repeat(200) {
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(25)
        }
        error("never: $what")
    }

    private fun displayedWith(tag: String, text: String? = null): Boolean {
        val matcher = if (text == null) hasTestTag(tag) else hasTestTag(tag) and hasText(text)
        val nodes = compose.onAllNodes(matcher)
        return (0 until nodes.fetchSemanticsNodes().size).any { runCatching { nodes[it].isDisplayed() }.getOrDefault(false) }
    }

    private fun pager(): SemanticsNodeInteraction = compose.onAllNodes(hasTestTag(CAROUSEL_PAGER_TAG))[0]

    @Test
    fun theSettledVideoItemGetsThePlayerAndLosesItWhenSwipedAway() {
        // The dots come with the items (the visit read them), so the pager has its three pages.
        awaitUntil("the carousel's items") { displayedWith(CAROUSEL_DOTS_TAG) }
        compose.onAllNodes(hasTestTag(CAROUSEL_COUNTER_TAG))[0].assertTextEquals("1/3")
        compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).assertCountEquals(0)

        pager().performTouchInput { swipeLeft() }
        awaitUntil("item 2 of 3") { displayedWith(CAROUSEL_COUNTER_TAG, "2/3") }
        compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).assertCountEquals(0) // an image item: nothing plays

        pager().performTouchInput { swipeLeft() }
        awaitUntil("the video item to get the player") { displayedWith(VIDEO_SURFACE_TAG) }

        pager().performTouchInput { swipeRight() }
        awaitUntil("item 2 of 3 again") { displayedWith(CAROUSEL_COUNTER_TAG, "2/3") }
        awaitUntil("the player to leave the video item") { compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).fetchSemanticsNodes().isEmpty() }
    }
}
