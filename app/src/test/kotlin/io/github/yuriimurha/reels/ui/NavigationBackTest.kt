package io.github.yuriimurha.reels.ui

import android.content.Context
import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.CollectionMediaEntity
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.closingAfterComposition
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import io.github.yuriimurha.reels.ui.viewer.POST_MEDIA_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Opening a post and going back (spec 2026-10-12 §3.6): the post grows from its tile, and shrinks back into the tile of the post
 * the viewer is on, which the grid brings into view; with the system's animations off, nothing moves. The real [ReelsNavHost]
 * over a real (file-backed) Room database, starting on a grid. The transitions are read from the viewer's media box
 * ([POST_MEDIA_TAG]), which the shared element lays out at its animated bounds, frame by frame on a paused clock. The page
 * pushes between Home and Search (R24) are read the same way from the pages' own nodes, and from the screen's pixels.
 */
@RunWith(AndroidJUnit4::class)
class NavigationBackTest {
    val compose = createComposeRule()

    /** The database closes only once the compose rule has disposed the screen (Task 6 minor 5): its flows query it until then. */
    @get:Rule
    val rules = closingAfterComposition(compose) { if (::container.isInitialized) container.db.close() }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var navController: NavHostController
    private lateinit var back: OnBackPressedDispatcher
    private val source = MediaSource.Collection(ALL_SAVED_ID)
    private val homeTitle = hasText("Saved")
    private val searchField = hasTestTag("searchField")

    @Before
    fun setUp() {
        container = AppContainer(context)
    }

    /** [count] images in All Saved, newest (highest sortKey) first, so item `i` is `m{count-1-i}`. */
    private fun seed(count: Int) = runBlocking {
        container.db.mediaDao().upsert(List(count) { mediaEntity("m$it", MediaType.IMAGE, thumbPath = null) })
        container.db.collectionDao().upsertMemberships(
            List(count) { CollectionMediaEntity(ALL_SAVED_ID, "m$it", sortKey = it.toLong(), lastSeenRunId = 1) },
        )
    }

    private fun showGrid() {
        compose.setContent {
            navController = rememberNavController()
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ReelsNavHost(navController, startDestination = GridRoute(source.encode(), "All Saved"))
                }
            }
        }
        compose.waitForIdle()
        awaitUntil("the grid's tiles") { compose.onAllNodesWithTag("tile").fetchSemanticsNodes().size >= 2 }
    }

    /** Room and Paging load off the main thread: poll until [condition] holds. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        repeat(200) {
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(25)
        }
        error("never: $what")
    }

    private fun tileOf(pk: String) = hasTestTag("tile") and hasText("@author_$pk")

    private fun boundsOfTile(pk: String): Rect = compose.onNode(tileOf(pk)).fetchSemanticsNode().boundsInRoot

    private fun root(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    /** The viewer's media on screen (its neighbour pages are composed too, out of sight), or null when there is none. */
    private fun shownMedia(): Rect? =
        compose.onAllNodesWithTag(POST_MEDIA_TAG).fetchSemanticsNodes().map { it.boundsInRoot }.singleOrNull { it.width > 0f && it.height > 0f }

    private fun onTheViewer() = navController.currentBackStack.value.any { it.destination.hasRoute<ViewerRoute>() }

    private fun tilesShown() = compose.onAllNodesWithTag("tile").fetchSemanticsNodes().isNotEmpty()

    /** On a paused clock, one frame at a time ([frames] of them), recording the viewer's media after each. */
    private fun recordFrames(frames: Int, each: () -> Unit = {}): List<Rect?> = List(frames) {
        compose.mainClock.advanceTimeByFrame()
        Thread.sleep(10) // lets Room's and Paging's background loads land between frames
        compose.waitForIdle()
        each()
        shownMedia()
    }

    private fun openTile(index: Int, gridEntry: NavBackStackEntry) {
        compose.onAllNodesWithTag("tile")[index].performClick()
        awaitUntil("the viewer on index $index") { onTheViewer() && gridEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY) == index }
    }

    /** One page a swipe (the pager snaps to the next page), until the viewer has settled on [index]. */
    private fun swipeTo(index: Int, gridEntry: NavBackStackEntry) {
        val from = gridEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY)!!
        for (next in from + 1..index) {
            compose.onRoot().performTouchInput { swipeUp() }
            awaitUntil("the viewer to settle on index $next") { gridEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY) == next }
        }
    }

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 2f, "$what: expected about $expected, was $actual")

    /** The picture part of a seeded tile (its name and caption lie below): every seed is 1080 x 1920, so 9:16. */
    private fun pictureOf(tile: Rect) = Rect(tile.left, tile.top, tile.right, tile.top + tile.width * 16f / 9f)

    /**
     * The viewer's media box lies on [picture]: the shared bounds scale the whole page to the picture's width (the page's own
     * picture is as wide as the page), centred on it. Scaled, not laid out again at the tile's size: the box keeps the page's
     * shape (a playing video's surface is never resized mid-transition).
     */
    private fun assertOnPicture(picture: Rect, media: Rect, what: String) {
        assertNear(picture.left, media.left, "$what left")
        assertNear(picture.right, media.right, "$what right")
        assertNear(picture.center.y, media.center.y, "$what centre")
        assertNear(media.width * root().height / root().width, media.height, "$what height, at the page's shape")
    }

    @Test
    fun backFromTheViewerReturnsToTheTileOfTheLastPost() {
        seed(30)
        showGrid()
        val gridEntry = navController.currentBackStackEntry!!
        openTile(0, gridEntry)
        assertEquals(0, navController.currentBackStackEntry!!.toRoute<ViewerRoute>().index)
        swipeTo(20, gridEntry) // item 20 is m9
        compose.onNode(tileOf("m9")).assertDoesNotExist() // the grid is not composed under the viewer

        compose.runOnUiThread { back.onBackPressed() } // the system Back, as the gesture's commit delivers it
        compose.waitForIdle()

        assertFalse(onTheViewer(), "Back left the viewer on the stack")
        assertEquals(gridEntry, navController.currentBackStackEntry)
        assertEquals(0, compose.onAllNodesWithContentDescription("Open on Instagram").fetchSemanticsNodes().size, "the viewer is still shown")
        compose.onNode(tileOf("m9")).assertIsDisplayed()
    }

    @Test
    fun openingAPostGrowsItFromItsTile() {
        seed(30)
        showGrid()
        val tile = boundsOfTile("m28") // item 1: the right column's first tile
        val neighbour = boundsOfTile("m29")
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithTag("tile")[1].performClick()
        val gridUnder = mutableListOf<Rect?>()
        val media = recordFrames(60) {
            gridUnder += compose.onAllNodes(tileOf("m29")).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        }.filterNotNull()
        compose.mainClock.autoAdvance = true

        // It starts on the tile's picture...
        assertOnPicture(pictureOf(tile), media.first(), "start")
        // ...grows through sizes between the tile and the screen...
        assertTrue(media.any { it.width > tile.width + 20f && it.width < root().width - 20f }, "no in-between size: $media")
        // The grid stays, unmoved, under the viewer while it fades in (no shared-axis slide)...
        assertTrue(gridUnder.take(10).all { it == neighbour }, "the grid moved or left under the opening viewer: $gridUnder")
        // ...and the post ends filling the screen, the grid gone from under it.
        assertEquals(root(), media.last())
        assertFalse(tilesShown(), "the grid stayed under the open viewer")
    }

    @Test
    fun backShrinksThePostIntoTheTileOfThePostTheViewerIsOn() {
        seed(30)
        showGrid()
        val gridEntry = navController.currentBackStackEntry!!
        openTile(0, gridEntry)
        swipeTo(6, gridEntry) // item 6 is m23, below the first screen of the grid
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { back.onBackPressed() }
        val tileDuring = mutableListOf<Rect>()
        val media = recordFrames(60) { compose.onAllNodes(tileOf("m23")).fetchSemanticsNodes().singleOrNull()?.let { tileDuring += it.boundsInRoot } }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        val shrinking = media.takeWhile { it != null }.filterNotNull()
        val tile = boundsOfTile("m23")
        // The grid is simply there under the fading viewer (no shared-axis slide), its tile still from the first frame it shows.
        assertTrue(tileDuring.isNotEmpty() && tileDuring.all { it == tile }, "m23's tile moved while the post shrank into it: $tileDuring")
        assertEquals(root(), shrinking.first())
        assertTrue(shrinking.any { it.width > tile.width + 20f && it.width < root().width - 20f }, "no in-between size: $shrinking")
        // At its smallest, the media lies on m23's picture, where the grid brought m23's tile.
        assertOnPicture(pictureOf(tile), shrinking.minBy { it.width }, "end")
        assertTrue(media.last() == null && !onTheViewer(), "the viewer is still there")
        compose.onNode(tileOf("m23")).assertIsDisplayed()
    }

    /**
     * Back on the post that was opened, whose tile is wholly in view: the grid stays where it was (it does not bring the tile to
     * the top), and the post lands where it was tapped.
     */
    @Test
    fun backToATileInViewLeavesTheGridWhereItWas() {
        seed(30)
        showGrid()
        val gridEntry = navController.currentBackStackEntry!!
        // Scrolled so the second row (item 2 is m27) shows whole, below the top bar.
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        compose.waitForIdle()
        val before = boundsOfTile("m27")
        assertTrue(before.top > 64f + 20f && before.bottom <= root().bottom, "the setup: m27's tile is whole and below the top, $before")
        compose.onNode(tileOf("m27")).performClick()
        awaitUntil("the viewer on index 2") { onTheViewer() && gridEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY) == 2 }

        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()

        assertFalse(onTheViewer())
        assertEquals(before, boundsOfTile("m27"))
    }

    /** The back gesture, from the left edge, at [progress] (0 when it starts). */
    private fun gesture(progress: Float) = BackEventCompat(progress * 300f, 200f, progress, BackEventCompat.EDGE_LEFT)

    /**
     * Predictive back (spec 2026-10-12 §3.6): the gesture drives the shrink into the tile (not Navigation's own scale-out of the
     * page), and letting go short of Back cancels it.
     */
    @Test
    fun theBackGestureShrinksThePostIntoItsTileAndCanBeCancelled() {
        seed(30)
        showGrid()
        val gridEntry = navController.currentBackStackEntry!!
        openTile(0, gridEntry)
        swipeTo(6, gridEntry) // item 6 is m23
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { back.dispatchOnBackStarted(gesture(0f)) }
        recordFrames(4)
        compose.runOnUiThread { back.dispatchOnBackProgressed(gesture(0.5f)) }
        val halfway = recordFrames(4).last()!!
        val tile = boundsOfTile("m23")
        val screen = root()

        // Half-way, the post is between the screen and m23's tile, on its way there (a page scaled out stays centred).
        assertTrue(halfway.width < screen.width - 20f && halfway.width > tile.width + 10f, "not half-way: $halfway")
        assertTrue(
            abs(halfway.center.x - tile.center.x) < abs(halfway.center.x - screen.center.x),
            "half-way the post is not headed for its tile: $halfway, the tile $tile",
        )

        compose.runOnUiThread { back.dispatchOnBackCancelled() }
        val cancelled = recordFrames(40)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(screen, cancelled.last(), "the post did not return to full screen")
        assertTrue(onTheViewer(), "a cancelled gesture closed the viewer")

        // A gesture carried through goes back, onto the tile.
        compose.runOnUiThread {
            back.dispatchOnBackStarted(gesture(0f))
            back.dispatchOnBackProgressed(gesture(0.8f))
            back.onBackPressed()
        }
        compose.waitForIdle()
        assertFalse(onTheViewer(), "the gesture did not go back")
        compose.onNode(tileOf("m23")).assertIsDisplayed()
        // Under the gesture the grid stood where it ends up (it does not slide in under the shrinking post).
        assertEquals(boundsOfTile("m23"), tile)
    }

    /** The real nav host from Home, as the app opens. */
    private fun showHome() {
        compose.setContent {
            navController = rememberNavController()
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ReelsNavHost(navController, startDestination = HomeRoute)
                }
            }
        }
        compose.waitForIdle()
        awaitUntil("Home") { xOf(homeTitle) != null }
    }

    /**
     * R24: the back gesture between pages seeks the push's reverse, linearly, so the page follows the finger: the page closed
     * slides towards the end by as much of its width as the gesture's progress, unscaled, while the previous page comes back
     * from a third of the width towards the start by the same share.
     */
    @Test
    fun theBackGestureSlidesAPageOutWithTheFinger() {
        showHome()
        val homeAt = xOf(homeTitle)!!
        compose.runOnUiThread { navController.navigate(SearchRoute) }
        compose.waitForIdle()
        val searchAt = xOf(searchField)!!
        val size = compose.onNode(searchField).fetchSemanticsNode().size
        val width = root().width
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { back.dispatchOnBackStarted(gesture(0f)) }
        recordFrames(4)
        for (progress in listOf(0.25f, 0.5f)) {
            compose.runOnUiThread { back.dispatchOnBackProgressed(gesture(progress)) }
            recordFrames(4)
            assertNear(searchAt + progress * width, xOf(searchField)!!, "Search at $progress of the gesture")
            assertEquals(size, compose.onNode(searchField).fetchSemanticsNode().size, "Search's size at $progress of the gesture (the page is not scaled)")
            assertNear(homeAt - (1 - progress) * width / 3f, xOf(homeTitle)!!, "Home under Search at $progress of the gesture")
        }
        compose.mainClock.autoAdvance = true
    }

    /** The search page over the seeded library, searching "m" (every seed's author), with its results' tiles shown. */
    private fun showSearchResults() {
        compose.setContent {
            navController = rememberNavController()
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ReelsNavHost(navController, startDestination = SearchRoute)
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNode(hasTestTag("searchField")).performTextInput("m")
        compose.mainClock.advanceTimeBy(200) // the search's typing debounce
        compose.mainClock.autoAdvance = true
        awaitUntil("the results' tiles") { compose.onAllNodesWithTag("tile").fetchSemanticsNodes().size >= 2 }
    }

    /**
     * Task 6 minor 2, Search as the grid (R15): back on the result that was opened, wholly in view, the results stay where they
     * were; and while the post opens, the results under it stay too (they do not slide away under the growing post).
     */
    @Test
    @Config(qualifiers = "w320dp-h900dp")
    fun backToASearchResultInViewLeavesTheResultsWhereTheyWere() {
        seed(30)
        showSearchResults()
        val searchEntry = navController.currentBackStackEntry!!
        val before = boundsOfTile("m27") // item 2: the second row, whole on this tall screen
        val neighbour = boundsOfTile("m26")
        val top = compose.onNode(hasTestTag("searchField")).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(before.top > top + 20f && before.bottom <= root().bottom, "the setup: m27's tile is whole and below the top, $before")

        compose.mainClock.autoAdvance = false
        compose.onNode(tileOf("m27")).performClick()
        val under = List(10) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onAllNodes(tileOf("m26")).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
        }
        compose.mainClock.autoAdvance = true
        assertTrue(under.all { it == neighbour }, "the results moved or left under the opening viewer: $under")
        awaitUntil("the viewer on index 2") { onTheViewer() && searchEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY) == 2 }

        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()

        assertFalse(onTheViewer())
        assertEquals(before, boundsOfTile("m27"))
    }

    /** Task 6 minor 2: back from a result far down the list, the results bring its tile into view, as the grid does. */
    @Test
    fun backFromAFarSearchResultShowsItsTile() {
        seed(30)
        showSearchResults()
        val searchEntry = navController.currentBackStackEntry!!
        openTile(0, searchEntry)
        swipeTo(20, searchEntry) // item 20 is m9
        compose.onNode(tileOf("m9")).assertDoesNotExist()

        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()

        assertFalse(onTheViewer())
        assertEquals(searchEntry, navController.currentBackStackEntry)
        compose.onNode(tileOf("m9")).assertIsDisplayed()
    }

    /** The x of the node matching [matcher], unclipped (a sliding page may put it off screen), or null when it is not composed. */
    private fun xOf(matcher: SemanticsMatcher): Float? =
        compose.onAllNodes(matcher).fetchSemanticsNodes().singleOrNull()?.positionInRoot?.x

    /** On a paused clock, [frames] frames, recording the x of [a] and of [b] after each. */
    private fun recordXs(frames: Int, a: SemanticsMatcher, b: SemanticsMatcher): List<Pair<Float?, Float?>> = List(frames) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        xOf(a) to xOf(b)
    }

    private fun List<Float>.falls() = zipWithNext().all { (x, y) -> y <= x + 0.5f } && first() > last() + 2f

    private fun List<Float>.rises() = zipWithNext().all { (x, y) -> y >= x - 0.5f } && first() < last() - 2f

    /**
     * A push's pace, from a page's offsets one frame (16 ms) apart from the start: it is over in about 300 ms (its last move lands
     * within [PUSH_FRAMES] frames), and it slows into its end, an eased curve: its last three moves add up to less than its
     * fastest one (a linear slide moves alike every frame).
     */
    private fun assertPacedLikeAPush(offsets: List<Float>, what: String) {
        val moves = offsets.zipWithNext { a, b -> abs(b - a) }
        val last = moves.indexOfLast { it > 0.5f }
        assertTrue(last in 2 until PUSH_FRAMES, "$what still moved $last frames on: $offsets")
        assertTrue(moves.subList(last - 2, last + 1).sum() < moves.max(), "$what did not slow into its end: $offsets")
    }

    /**
     * R24 (spec 2026-10-12, amending §3.6's shared axis): pages push. Going forward, the new page slides in from the end edge,
     * its whole width (its content starts a screen's width right of where it settles, and moves left), while the page left
     * drifts a third of the width towards the start. A plain Back is the exact reverse: the page closed slides out to the end
     * edge while the previous one comes back from a third towards the start. Each is over in about 300 ms and slows into its
     * end. This also pins that the nav host's four page transitions are not None while motion is on.
     */
    @Test
    fun pagesPushInFromTheEndAndBackOutTheSameWay() {
        showHome()
        val homeAt = xOf(homeTitle)!!
        val width = root().width
        val third = width / 3f

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { navController.navigate(SearchRoute) }
        val forward = recordXs(30, searchField, homeTitle)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val searchAt = xOf(searchField)!!
        val entering = forward.mapNotNull { it.first?.minus(searchAt) }
        assertTrue(
            entering.size >= 3 && entering.first() in width * 0.9f..width + 0.5f && entering.falls() && entering.last() == 0f,
            "Search did not slide in from the end edge: $entering",
        )
        val leaving = forward.mapNotNull { it.second?.minus(homeAt) }
        assertTrue(
            leaving.size >= 3 && leaving.all { it in -third - 1f..0.5f } && leaving.min() < -third * 0.95f && leaving.falls(),
            "Home did not drift a third of the width to the start: $leaving",
        )
        assertPacedLikeAPush(entering, "Search coming in")
        assertPacedLikeAPush(leaving, "Home leaving")

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { back.onBackPressed() }
        val backward = recordXs(30, searchField, homeTitle)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val closing = backward.mapNotNull { it.first?.minus(searchAt) }
        assertTrue(
            closing.size >= 3 && closing.all { it in -0.5f..width + 0.5f } && closing.max() > width * 0.95f && closing.rises(),
            "Search did not slide out to the end edge: $closing",
        )
        val returning = backward.mapNotNull { it.second?.minus(homeAt) }
        assertTrue(
            returning.size >= 3 && returning.first() in -third - 1f..-third * 0.9f && returning.rises() && returning.last() == 0f,
            "Home did not come back from a third of the width towards the start: $returning",
        )
        assertPacedLikeAPush(closing, "Search closing")
        assertPacedLikeAPush(returning, "Home coming back")
        assertEquals(homeAt, xOf(homeTitle))
    }

    /** A screenshot, as ARGB pixels. */
    private class Shot(val width: Int, val height: Int, private val argb: IntArray) {
        operator fun get(x: Int, y: Int) = argb[y * width + x]

        /** The colour most of it has: its page's background. */
        val background: Int by lazy { argb.asList().groupingBy { it }.eachCount().maxBy { it.value }.key }
    }

    /** The screen as it is drawn now (Robolectric's native graphics). */
    private fun shot(): Shot {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        return Shot(pixels.width, pixels.height, IntArray(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width].toArgb() })
    }

    /** The two colours differ by no more than a rounding in any channel. */
    private fun alike(a: Int, b: Int) = (0 until 32 step 8).all { abs((a ushr it and 0xFF) - (b ushr it and 0xFF)) <= 8 }

    /**
     * [page], a shot of a page at rest, shows unchanged in [frame], drawn [dx] px to the side, over the columns [xs]: at least
     * 95 % of its pixels landing there that would look otherwise if the page were not on top, or faded, are in [frame] as they
     * are (there must be some). Otherwise is [under], a shot of the page under it drawn [underDx] px to the side, where that
     * page lies; elsewhere the page's own background.
     */
    private fun assertShows(frame: Shot, page: Shot, dx: Int, xs: IntRange, what: String, under: Shot? = null, underDx: Int = 0) {
        var telling = 0
        var shown = 0
        for (y in 0 until page.height) for (x in 0 until page.width) {
            val at = x + dx
            if (at !in xs || at !in 0 until frame.width) continue
            val pixel = page[x, y]
            val otherwise = if (under != null && at - underDx in 0 until under.width) under[at - underDx, y] else page.background
            if (alike(pixel, otherwise)) continue
            telling++
            if (alike(frame[at, y], pixel)) shown++
        }
        assertTrue(telling >= 20 && shown >= telling * 0.95f, "$what: $shown of the page's $telling telling pixels in the columns $xs show as they are")
    }

    /**
     * R24: the page that moves is drawn on top, and nothing fades, read from the screen's pixels frame by frame. Going forward
     * the new page slides in over the page left; going back, and under the back gesture, the page closed slides out over the
     * page coming back (Navigation 2.10 draws the page left above the other on a pop). So right of Search's edge the screen
     * shows Search's pixels exactly as they are at rest, where Home's would differ (Search is on top, and not faded), and left
     * of it Home's (not faded).
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theMovingPageIsDrawnOnTopAndNothingFades() {
        showHome()
        val homeAt = xOf(homeTitle)!!
        compose.runOnUiThread { navController.navigate(SearchRoute) }
        compose.waitForIdle()
        val searchAt = xOf(searchField)!!
        val search = shot()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        val home = shot()

        /** Checks the frame drawn now, if Search's edge is well inside the screen; whether it did. */
        fun checked(what: String): Boolean {
            val top = ((xOf(searchField) ?: return false) - searchAt).roundToInt()
            val under = ((xOf(homeTitle) ?: return false) - homeAt).roundToInt()
            if (top !in home.width / 5..home.width * 4 / 5) return false
            val frame = shot()
            assertShows(frame, search, top, top until frame.width, "$what, Search over Home (Search at $top)", home, under)
            assertShows(frame, home, under, 0 until top, "$what, Home beside Search (Home at $under)")
            return true
        }

        fun framesChecked(what: String) = List(30) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            checked(what)
        }.count { it }

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { navController.navigate(SearchRoute) }
        val forward = framesChecked("forward")
        compose.runOnUiThread { back.onBackPressed() }
        val backward = framesChecked("back")
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { navController.navigate(SearchRoute) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { back.dispatchOnBackStarted(gesture(0f)) }
        recordFrames(4)
        val seeked = listOf(0.3f, 0.6f).count { progress ->
            compose.runOnUiThread { back.dispatchOnBackProgressed(gesture(progress)) }
            recordFrames(4)
            checked("at $progress of the back gesture")
        }
        compose.mainClock.autoAdvance = true

        assertTrue(forward >= 3 && backward >= 3 && seeked == 2, "too few frames checked: $forward forward, $backward back, $seeked of the gesture")
    }

    /** With the system's animations off (scale 0): no growing, no shrinking, no fade; each page is there on the next frames. */
    @Test
    fun withAnimationsOffAPostOpensAndClosesWithoutMotion() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        seed(30)
        showGrid()
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithTag("tile")[1].performClick()
        val opening = recordFrames(FRAMES_WITHOUT_MOTION)
        assertFalse(tilesShown(), "the grid was still there $FRAMES_WITHOUT_MOTION frames after the tap")
        assertTrue(opening.any { it != null }, "the viewer's media never showed: $opening")
        assertTrue(opening.filterNotNull().all { it == root() }, "the media did not open full screen at once: $opening")

        compose.runOnUiThread { back.onBackPressed() }
        val closing = recordFrames(FRAMES_WITHOUT_MOTION)
        compose.mainClock.autoAdvance = true
        assertTrue(closing.filterNotNull().all { it == root() }, "the media shrank: $closing")
        assertTrue(closing.last() == null && !onTheViewer(), "the viewer was still there $FRAMES_WITHOUT_MOTION frames after Back")
        assertTrue(tilesShown())
    }

    private companion object {
        /** A transition with no animation ends within a few frames; the 300 ms fades and the bounds spring take 20 or more. */
        const val FRAMES_WITHOUT_MOTION = 5

        /** A 300 ms push is over within this many 16 ms frames of its start (19 frames, and the first few to compose the page). */
        const val PUSH_FRAMES = 24
    }
}
