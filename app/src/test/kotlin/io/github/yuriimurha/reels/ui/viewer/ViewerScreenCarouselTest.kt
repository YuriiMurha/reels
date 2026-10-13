package io.github.yuriimurha.reels.ui.viewer

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import io.github.yuriimurha.reels.testutil.closingAfterComposition
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The copy of an offline item never viewed (R16), written out: the tests check the words, not a constant. */
private const val OFFLINE = "Offline: this item isn't cached yet"

/**
 * The real viewer over the Mock-mode container (debug build, fake backend, the bundled clip), with a player the test can
 * watch: carousel A (image, image, video), then carousel B (image, image, video, image, image), then a reel. The settled item
 * of the settled post is the unit: an image item stops the player, a video item sets it, and nothing of one carousel ever
 * reaches the next.
 */
@RunWith(AndroidJUnit4::class)
class ViewerScreenCarouselTest {
    val compose = createComposeRule()

    /** The player and the database go only once the compose rule has disposed the screen, which uses both until then. */
    @get:Rule
    val rules = closingAfterComposition(compose) {
        if (::exo.isInitialized) exo.release()
        if (::container.isInitialized) container.db.close()
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var exo: ExoPlayer
    private lateinit var player: RecordingPlayer

    /** The indices the screen reported as settled (`onIndexSettled`), in order. */
    private val settledIndices = mutableListOf<Int>()
    private val a = mediaEntity("1000001", MediaType.CAROUSEL, author = "creator_1").copy(carouselCount = 3)
    private val b = mediaEntity("1000003", MediaType.CAROUSEL, author = "creator_3").copy(carouselCount = 5)
    private val reel = mediaEntity("1000002", MediaType.REEL, author = "creator_2")

    /**
     * What the screen does to the one player: `stop`, `clear`, `set:<cache key>`; and the listeners it added, to raise an error.
     * Every call is also checked for its thread: Media3 allows the main thread only ([offMain], asserted after each test).
     */
    private class RecordingPlayer(player: Player) : ForwardingPlayer(player) {
        val events = mutableListOf<String>()
        val listeners = mutableListOf<Player.Listener>()
        val offMain: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private fun onMain(call: String) {
            if (Looper.myLooper() != Looper.getMainLooper()) offMain += "$call on ${Thread.currentThread().name}"
        }

        override fun setMediaItem(mediaItem: MediaItem) {
            onMain("setMediaItem")
            events += "set:${mediaItem.localConfiguration?.customCacheKey}"
            super.setMediaItem(mediaItem)
        }

        override fun prepare() {
            onMain("prepare")
            super.prepare()
        }

        override fun play() {
            onMain("play")
            super.play()
        }

        override fun stop() {
            onMain("stop")
            events += "stop"
            super.stop()
        }

        override fun clearMediaItems() {
            onMain("clearMediaItems")
            events += "clear"
            super.clearMediaItems()
        }

        override fun addListener(listener: Player.Listener) {
            listeners += listener
            super.addListener(listener)
        }

        /** What Media3 tells its listeners when the first frame of the item it plays reaches the surface (R26: the poster goes). */
        @androidx.annotation.OptIn(UnstableApi::class)
        fun renderFirstFrame() {
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_RENDERED_FIRST_FRAME).build())
            for (listener in listeners.toList()) {
                listener.onRenderedFirstFrame()
                listener.onEvents(this, events)
            }
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
                    ViewerScreen(MediaSource.Collection(ALL_SAVED_ID), startIndex = 0, onIndexSettled = { settledIndices += it }, onBack = {}, player = player)
                }
            }
        }
    }

    @After
    fun tearDown() {
        // Task 6 minor 1: every call the screen made on the player came on the main thread.
        assertEquals(emptyList(), player.offMain.toList(), "player calls off the main thread")
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

    /** An icon button's words are its content description. */
    private fun displayedDescription(description: String): Boolean {
        val nodes = compose.onAllNodesWithContentDescription(description)
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

    /**
     * R26, end to end in Mock mode: opening A fetches its video item's poster with its pictures (the fake CDN's placeholder),
     * and when the owner swipes to that item, the poster covers the video until the player renders its first frame.
     */
    @Test
    fun aVideoItemShowsItsPosterFetchedAheadUntilItsFirstFrame() {
        onCarouselA()
        awaitUntil("the video item's poster, fetched ahead") { container.carouselImages.path(a.pk, 2) != null }
        repeat(2) { i ->
            pager().performTouchInput { swipeLeft() }
            awaitUntil("A's item ${i + 2} of 3") { displayedWith(CAROUSEL_COUNTER_TAG, "${i + 2}/3") }
        }
        awaitUntil("the video item to get the player") { player.events.lastOrNull() == "set:${a.pk}_2" && displayedWith(VIDEO_SURFACE_TAG) }
        assertTrue(displayedWith(carouselPosterTag(2)), "its poster covers the surface until the first frame")

        compose.runOnIdle { player.renderFirstFrame() }

        awaitUntil("the poster to go") { !displayedWith(carouselPosterTag(2)) }
        assertTrue(displayedWith(VIDEO_SURFACE_TAG), "the video shows")
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
        assertTrue(displayedDescription("Open on Instagram"), "the way out is right there")
        assertTrue(displayedWith(CAROUSEL_COUNTER_TAG, "3/3"), "still on the video item")
        assertEquals(listOf("stop", "clear"), player.events.takeLast(2), "the player was stopped and emptied")
    }

    /** R16: offline, a video item never watched through says so on the item, as an unviewed image does; not "Can't load this item". */
    @Test
    fun anUncachedVideoItemThePlayerCannotReachSaysItIsOffline() {
        onCarouselA(items = 2)
        awaitUntil("A's video item to play") { player.events.lastOrNull() == "set:${a.pk}_2" }

        compose.runOnIdle {
            val error = PlaybackException("offline", IOException("no connection"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            player.listeners.toList().forEach { it.onPlayerError(error) }
        }

        awaitUntil("the item's message") { compose.onAllNodesWithText(OFFLINE).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(OFFLINE).assertCountEquals(1)
        compose.onNodeWithText(OFFLINE).assertIsDisplayed()
        compose.onAllNodesWithText("Can't load this item").assertCountEquals(0)
        assertTrue(displayedWith(CAROUSEL_COUNTER_TAG, "3/3"), "still on the video item")
    }

    /** Like [awaitUntil], but answers whether [condition] came to hold, so the assertion after it can say what happened instead. */
    private fun cameTrue(condition: () -> Boolean): Boolean {
        repeat(80) {
            compose.waitForIdle()
            if (condition()) return true
            Thread.sleep(25)
        }
        return false
    }

    /**
     * C1: a sync saves a new post above the settled carousel, so the carousel moves one place down. The list changes at once,
     * but the pager moves the carousel's key to its new index only in its next measure: for that instant its settled index
     * holds the new post. Only a pair the pager's own layout agrees with is a settle, so the carousel keeps its visit and its
     * item (its video item restarts, as a reel does when the list moves), and the post that slid in never gets the player.
     */
    @Test
    fun aPostSavedAboveTheSettledCarouselKeepsItsItemAndNeverPlaysTheNewPost() {
        onCarouselA(items = 2)
        awaitUntil("A's video item to play") { player.events.lastOrNull() == "set:${a.pk}_2" }
        val mark = player.events.size
        val settledMark = settledIndices.size

        val saved = mediaEntity("1000009", MediaType.REEL, author = "creator_9")
        runBlocking {
            container.db.mediaDao().upsert(listOf(saved))
            container.db.collectionDao().upsertMemberships(listOf(CollectionMediaEntity(ALL_SAVED_ID, saved.pk, sortKey = 10, lastSeenRunId = 2)))
        }

        awaitUntil("A to settle at its new index") { 1 in settledIndices.drop(settledMark) }
        cameTrue { since(mark).lastOrNull() == "set:${a.pk}_2" }
        assertEquals(listOf(1), settledIndices.drop(settledMark), "one settle: A at its new index, never the new post at A's old one")
        assertEquals(listOf("stop", "clear", "set:${a.pk}_2"), since(mark), "A's own video item again; the new post's video never set")
        assertTrue(displayedWith(CAROUSEL_COUNTER_TAG, "3/3"), "A keeps its item")
    }

    /**
     * C1, the other way: a sync's reconcile removes the post above the settled carousel (marked removed, its membership
     * deleted), so the carousel moves one place up. For an instant its old index holds the post below it; that is no settle.
     */
    @Test
    fun aPostRemovedAboveTheSettledCarouselKeepsItsItemAndNeverPlaysThePostBelow() {
        onCarouselA()
        pager().performTouchInput { swipeUp() }
        awaitUntil("B's items") { displayedWith(CAROUSEL_COUNTER_TAG, "1/5") && displayedWith(CAROUSEL_DOTS_TAG) }
        repeat(2) { i ->
            pager().performTouchInput { swipeLeft() }
            awaitUntil("B's item ${i + 2} of 5") { displayedWith(CAROUSEL_COUNTER_TAG, "${i + 2}/5") }
        }
        awaitUntil("B's video item to play") { player.events.lastOrNull() == "set:${b.pk}_2" }
        val mark = player.events.size
        val settledMark = settledIndices.size

        runBlocking {
            container.db.mediaDao().markRemoved(listOf(a.pk), at = 1)
            container.db.collectionDao().deleteAllMembershipsOf(listOf(a.pk))
        }

        awaitUntil("B to settle at its new index") { 0 in settledIndices.drop(settledMark) }
        cameTrue { since(mark).lastOrNull() == "set:${b.pk}_2" }
        assertEquals(listOf(0), settledIndices.drop(settledMark), "one settle: B at its new index, never the reel at B's old one")
        assertEquals(listOf("stop", "clear", "set:${b.pk}_2"), since(mark), "B's own video item again; the reel's video never set")
        assertTrue(displayedWith(CAROUSEL_COUNTER_TAG, "3/5"), "B keeps its item")
    }
}
