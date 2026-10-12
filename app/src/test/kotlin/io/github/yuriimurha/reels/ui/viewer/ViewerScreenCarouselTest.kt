package io.github.yuriimurha.reels.ui.viewer

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CarouselItemEntity
import io.github.yuriimurha.reels.data.db.CollectionMediaEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real viewer over the Mock-mode container (debug build, fake backend, the bundled clip), with a player the test can
 * watch: carousel A (image, image, video), then carousel B (image, image, video, image, image), then a reel. The settled item
 * of the settled post is the unit: an image item stops the player, a video item sets it, and nothing of one carousel ever
 * reaches the next.
 */
@RunWith(AndroidJUnit4::class)
class ViewerScreenCarouselTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var exo: ExoPlayer
    private lateinit var player: RecordingPlayer
    private val a = mediaEntity("1000001", MediaType.CAROUSEL, author = "creator_1").copy(carouselCount = 3)
    private val b = mediaEntity("1000003", MediaType.CAROUSEL, author = "creator_3").copy(carouselCount = 5)
    private val reel = mediaEntity("1000002", MediaType.REEL, author = "creator_2")

    /** What the screen does to the one player: `stop`, `clear`, `set:<cache key>`; and the listeners it added, to raise an error. */
    private class RecordingPlayer(player: Player) : ForwardingPlayer(player) {
        val events = mutableListOf<String>()
        val listeners = mutableListOf<Player.Listener>()

        override fun setMediaItem(mediaItem: MediaItem) {
            events += "set:${mediaItem.localConfiguration?.customCacheKey}"
            super.setMediaItem(mediaItem)
        }

        override fun stop() {
            events += "stop"
            super.stop()
        }

        override fun clearMediaItems() {
            events += "clear"
            super.clearMediaItems()
        }

        override fun addListener(listener: Player.Listener) {
            listeners += listener
            super.addListener(listener)
        }
    }

    @Before
    fun setUp() {
        container = AppContainer(context)
        assertTrue(container.usesFake, "these tests run on the Mock-mode container")
        runBlocking {
            container.db.mediaDao().upsert(listOf(a, b, reel))
            container.db.collectionDao().upsertMemberships(
                listOf(a to 3L, b to 2L, reel to 1L).map { (media, key) -> CollectionMediaEntity(ALL_SAVED_ID, media.pk, sortKey = key, lastSeenRunId = 1) },
            )
            store(a)
            store(b)
        }
        exo = ExoPlayer.Builder(context).build()
        player = RecordingPlayer(exo)
        compose.setContent {
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ViewerScreen(MediaSource.Collection(ALL_SAVED_ID), startIndex = 0, onIndexSettled = {}, onBack = {}, player = player)
                }
            }
        }
    }

    @After
    fun tearDown() {
        exo.release()
        container.db.close()
    }

    /** [media]'s items as a sync stores them: every third one (positions 2, 5, ...) a video, as in the fake library. */
    private suspend fun store(media: MediaEntity) = container.db.carouselItemDao().replace(
        media.pk,
        List(media.carouselCount!!) { position ->
            val video = position % 3 == 2
            CarouselItemEntity(
                media.pk, position, if (video) MediaType.VIDEO else MediaType.IMAGE, 1080, 1350,
                "fake://thumb/${media.pk}-$position", if (video) "fake://video/${media.pk}-$position" else null, null,
            )
        },
    )

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

    private fun displayedText(text: String): Boolean {
        val nodes = compose.onAllNodesWithText(text)
        return (0 until nodes.fetchSemanticsNodes().size).any { runCatching { nodes[it].isDisplayed() }.getOrDefault(false) }
    }

    /** The settled post's carousel pager (a neighbouring carousel, composed off screen, has one too). */
    private fun pager(): SemanticsNodeInteraction {
        val nodes = compose.onAllNodes(hasTestTag(CAROUSEL_PAGER_TAG))
        return (0 until nodes.fetchSemanticsNodes().size).map { nodes[it] }.first { runCatching { it.isDisplayed() }.getOrDefault(false) }
    }

    /** What the screen did to the player since [mark] (an index into its events). */
    private fun since(mark: Int) = player.events.drop(mark)

    /** Opens on A and waits for its items (its dots); then swipes A's items left [items] times, waiting for each counter. */
    private fun onCarouselA(items: Int = 0) {
        awaitUntil("A's items") { displayedWith(CAROUSEL_DOTS_TAG) && displayedWith(CAROUSEL_COUNTER_TAG, "1/3") }
        repeat(items) { i ->
            pager().performTouchInput { swipeLeft() }
            awaitUntil("A's item ${i + 2} of 3") { displayedWith(CAROUSEL_COUNTER_TAG, "${i + 2}/3") }
        }
    }

    @Test
    fun theSettledVideoItemGetsThePlayerAndAnImageItemStopsIt() {
        onCarouselA()
        compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).assertCountEquals(0)

        var mark = player.events.size
        pager().performTouchInput { swipeLeft() }
        awaitUntil("item 2 of 3") { displayedWith(CAROUSEL_COUNTER_TAG, "2/3") }
        assertEquals(listOf("stop", "clear"), since(mark), "an image item: the player stopped, nothing set")

        mark = player.events.size
        pager().performTouchInput { swipeLeft() }
        awaitUntil("the video item to get the player") { displayedWith(VIDEO_SURFACE_TAG) }
        assertEquals(listOf("stop", "clear", "set:${a.pk}_2"), since(mark), "the video item, under its own key")

        mark = player.events.size
        pager().performTouchInput { swipeRight() }
        awaitUntil("item 2 of 3 again") { displayedWith(CAROUSEL_COUNTER_TAG, "2/3") }
        awaitUntil("the player to leave the video item") { compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("stop", "clear"), since(mark), "back on an image: stopped, so no sound goes on under it")
    }

    /** Review Focus 1: the next carousel starts at its item 0, with nothing of A's item (no second settle, no video). */
    @Test
    fun aSwipeFromOneCarouselToTheNextStartsItAtItemZeroAndPlaysNothingOfTheFirst() {
        onCarouselA(items = 2)
        awaitUntil("A's video item to play") { player.events.lastOrNull() == "set:${a.pk}_2" }

        val mark = player.events.size
        pager().performTouchInput { swipeUp() }
        awaitUntil("B's items") { displayedWith(CAROUSEL_COUNTER_TAG, "1/5") && displayedWith(CAROUSEL_DOTS_TAG) }
        compose.waitForIdle()

        assertEquals(listOf("stop", "clear"), since(mark), "one settle, B's item 0 (an image): never B's item 2")
        compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).assertCountEquals(0)
        compose.onNodeWithText("@creator_3").assertIsDisplayed()
    }

    /** The carousel below the settled one is composed off screen: it shows its own cover and count, never the settled one's items. */
    @Test
    fun aNeighbouringCarouselNeverShowsTheSettledOnesItems() {
        onCarouselA()

        assertEquals(1, compose.onAllNodes(hasTestTag(CAROUSEL_COUNTER_TAG) and hasText("1/5")).fetchSemanticsNodes().size, "B's own count")
        compose.onAllNodesWithTag(CAROUSEL_DOTS_TAG).assertCountEquals(1) // A's; B's items are not known until B is settled
    }

    /** A video item the player cannot play says so on that item (once: not in the overlay too), with Open on Instagram. */
    @Test
    fun aCarouselVideoItemThatCannotPlaySaysSoOnTheItem() {
        onCarouselA(items = 2)
        awaitUntil("A's video item to play") { player.events.lastOrNull() == "set:${a.pk}_2" }

        compose.runOnIdle {
            val error = PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
            player.listeners.toList().forEach { it.onPlayerError(error) }
        }

        awaitUntil("the item's message") { compose.onAllNodesWithText("Can't load this item").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Can't load this item").assertCountEquals(1)
        compose.onNodeWithText("Can't load this item").assertIsDisplayed()
        compose.onAllNodesWithTag(VIDEO_SURFACE_TAG).assertCountEquals(0)
        assertTrue(displayedText("Open on Instagram"), "the way out is right there")
        assertTrue(displayedWith(CAROUSEL_COUNTER_TAG, "3/3"), "still on the video item")
        assertEquals(listOf("stop", "clear"), player.events.takeLast(2), "the player was stopped and emptied")
    }
}
