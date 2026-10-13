package io.github.yuriimurha.reels.ui.common

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.KotlinSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Page and post motion (spec 2026-10-12 §3.6): none at all when the system's animator duration scale is 0; and the shared
 * media's two timings, a viewer page that shows during the open (it grows from the tile) and one that shows after it (in place).
 */
@RunWith(AndroidJUnit4::class)
class MotionTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun animatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    private fun enters() = mapOf(
        "pageEnter" to pageEnter(context),
        "pagePopEnter" to pagePopEnter(context),
        "pagePopEnter(gesture)" to pagePopEnter(context, gesture = true),
        "viewerEnter" to viewerEnter(context),
    )

    private fun exits() = mapOf(
        "pageExit" to pageExit(context),
        "pagePopExit" to pagePopExit(context),
        "pagePopExit(gesture)" to pagePopExit(context, gesture = true),
        "viewerExit" to viewerExit(context),
    )

    @Test
    fun noTransitionWhenAnimationsAreOff() {
        animatorScale(0f)
        assertFalse(motionEnabled(context))
        enters().forEach { (name, transition) -> assertEquals(EnterTransition.None, transition, name) }
        exits().forEach { (name, transition) -> assertEquals(ExitTransition.None, transition, name) }

        animatorScale(1f)
        assertTrue(motionEnabled(context))
        enters().forEach { (name, transition) -> assertNotEquals(EnterTransition.None, transition, name) }
        exits().forEach { (name, transition) -> assertNotEquals(ExitTransition.None, transition, name) }
    }

    /** [underViewerExit] needs a transition's scope (the nav host's): it is read from a real AnimatedContent's transition spec. */
    @Test
    fun thePageUnderTheViewerHasNoTransitionWhenAnimationsAreOff() {
        var target by mutableIntStateOf(0)
        val exitTo = mutableMapOf<Int, ExitTransition>()
        compose.setContent {
            AnimatedContent(target, transitionSpec = {
                exitTo[targetState] = underViewerExit(context)
                EnterTransition.None togetherWith ExitTransition.None
            }) { state -> Box(Modifier.size(1.dp).testTag("state-$state")) }
        }
        animatorScale(0f)
        compose.runOnIdle { target = 1 }
        compose.waitForIdle()
        animatorScale(1f)
        compose.runOnIdle { target = 2 }
        compose.waitForIdle()

        assertEquals(ExitTransition.None, exitTo[1])
        assertNotEquals(ExitTransition.None, exitTo.getValue(2))
    }

    @Test
    fun aSlowedOrSpedUpScaleStillAnimates() {
        animatorScale(0.5f)
        assertTrue(motionEnabled(context))
        animatorScale(10f)
        assertTrue(motionEnabled(context))
    }

    /**
     * A device that never wrote the setting animates (the system's default scale is 1). Robolectric presets the setting to 1, so
     * it is cleared first: the default is what is read.
     */
    @Test
    fun anUnsetScaleAnimates() {
        Settings.Global.putString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, null)
        assertEquals(null, Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE), "the setting is unset")
        assertTrue(motionEnabled(context))
    }

    /**
     * Task 6 minor 4: the nav host uses the transitions above, which [noTransitionWhenAnimationsAreOff] shows are not None while
     * motion is on. `NavigationBackTest` runs the page pushes frame by frame (their direction, distance, timing and pixels, and
     * the gesture's); the viewer's fades, which no semantics can see, and the gesture's slots are pinned here in the source.
     */
    @Test
    fun theNavHostUsesTheseTransitions() {
        val source = KotlinSource.code(File("src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt").readText())
            .replace(Regex("""\s+"""), " ")
        for (wiring in listOf(
            "enterTransition = { pageEnter(context) }",
            "exitTransition = { if (targetState.isViewer) underViewerExit(context) else pageExit(context) }",
            "popEnterTransition = { if (initialState.isViewer) EnterTransition.None else pagePopEnter(context) }",
            "popExitTransition = { pagePopExit(context) }",
            "predictivePopEnterTransition = { if (initialState.isViewer) EnterTransition.None else pagePopEnter(context, gesture = true) }",
            "predictivePopExitTransition = { if (initialState.isViewer) viewerExit(context) else pagePopExit(context, gesture = true) }",
            "screen<ViewerRoute>(enterTransition = { viewerEnter(context) }, exitTransition = { viewerExit(context) })",
        )) {
            assertTrue(wiring in source, "ReelsNavHost.kt must have: $wiring")
        }
    }

    @Test
    fun theSharedKeyIsMediaDashPk() {
        assertEquals("media-3141592", sharedMediaKey("3141592"))
    }

    /**
     * A grid tile and a viewer page sharing post 1's media, in a shared transition: the page's media shows once [ready] (the
     * viewer's first page loads off the main thread, and can come after its screen's transition).
     */
    private fun showTileThenPage(screen: () -> Int, ready: () -> Boolean) {
        compose.setContent {
            SharedTransitionLayout {
                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                    AnimatedContent(screen(), transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }) { shown ->
                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                            Box(Modifier.fillMaxSize()) {
                                when {
                                    shown == 0 -> Box(Modifier.padding(20.dp).size(40.dp).sharedMedia("1").testTag("tile"))
                                    ready() -> Box(Modifier.fillMaxSize().sharedMedia("1").testTag("page"))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** One frame on the paused clock, then the bounds of what carries [tag] (fetching them also lays the frame out). */
    private fun frame(tag: String) = compose.mainClock.advanceTimeByFrame().let {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsInRoot }
    }

    /** The viewer's page while the open is under way: its media starts on the tile and grows from there. */
    @Test
    fun aPageThatShowsDuringTheTransitionGrowsFromTheTile() {
        var screen by mutableIntStateOf(0)
        var ready by mutableStateOf(true)
        showTileThenPage({ screen }, { ready })
        val tile = compose.onNodeWithTag("tile").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        screen = 1
        val page = List(40) { frame("page") }.mapNotNull { it.singleOrNull() }
        // The whole page, scaled to the tile's width and centred on it.
        val start = page.first()
        listOf(tile.left to start.left, tile.right to start.right, tile.center.y to start.center.y).forEach { (expected, actual) ->
            assertTrue(abs(expected - actual) <= 1f, "the page did not start on the tile: $start, the tile $tile")
        }
        assertEquals(compose.onRoot().fetchSemanticsNode().boundsInRoot, page.last())
    }

    /**
     * The viewer's page shows only after the open transition has ended (a slow first page): its media shows in place, full
     * size, and never flies in from where the tile was (the tile is gone).
     */
    @Test
    fun aPageThatShowsAfterTheTransitionShowsInPlace() {
        var screen by mutableIntStateOf(0)
        var ready by mutableStateOf(false)
        showTileThenPage({ screen }, { ready })
        compose.mainClock.autoAdvance = false
        screen = 1
        var frames = 0
        while (frame("tile").isNotEmpty()) check(++frames < 100) { "the transition never ended" }
        ready = true
        val page = List(10) { frame("page") }.flatten()
        val screenBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(page.isNotEmpty() && page.all { it == screenBounds }, "the late page did not show in place: $page")
    }

    /** Previews and screen tests have no shared transition around them: the modifier must leave the content alone. */
    @Test
    fun sharedMediaIsANoOpOutsideASharedTransition() {
        compose.setContent { Box(Modifier.size(40.dp).sharedMedia("1").testTag("media")) }
        compose.onNodeWithTag("media").assertIsDisplayed()
    }
}
