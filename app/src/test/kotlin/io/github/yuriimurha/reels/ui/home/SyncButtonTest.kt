package io.github.yuriimurha.reels.ui.home

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Home's sync button (2026-10-21), read from what it draws: the plain glyph when idle, the glyph turning while a run is going
 * (still, and in the primary colour, with the system's animations removed), and a small red dot when something needs the
 * owner. Native graphics at xxhdpi, so the glyph's strokes and the dot are several pixels wide; the clock is paused, so the
 * turn is read frame by frame.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "xxhdpi")
class SyncButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var badge by mutableStateOf(SyncBadge.IDLE)
    private var taps = 0
    private var primary = Color.Unspecified
    private var error = Color.Unspecified

    @Before
    fun animationsOn() = animatorScale(1f)

    private fun animatorScale(scale: Float) {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    /** The button alone on the theme's surface, its clock paused, and one frame drawn. */
    private fun show(initial: SyncBadge) {
        badge = initial
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ReelsTheme {
                primary = MaterialTheme.colorScheme.primary
                error = MaterialTheme.colorScheme.error
                Surface { SyncButton(badge, onClick = { taps++ }) }
            }
        }
        frames(2)
    }

    private fun frames(count: Int) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    private fun button() = compose.onNodeWithTag(SYNC_BUTTON_TAG)

    /** The button's pixels, as ARGB. */
    private fun shot(): IntArray {
        val pixels = button().captureToImage().toPixelMap()
        return IntArray(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width].toArgb() }
    }

    private fun alike(a: Int, b: Int) = (0 until 32 step 8).all { abs((a ushr it and 0xFF) - (b ushr it and 0xFF)) <= 8 }

    /** How many of the button's pixels are [colour]. */
    private fun IntArray.countOf(colour: Color): Int = count { alike(it, colour.toArgb()) }

    /** The same picture a quarter of a turn's time later. */
    private fun staysStill(): Boolean {
        val before = shot()
        compose.mainClock.advanceTimeBy(SYNC_TURN_MS / 4L)
        frames(1)
        return before.contentEquals(shot())
    }

    @Test
    fun idleIsThePlainGlyph() {
        show(SyncBadge.IDLE)
        button().assertIsDisplayed().assertContentDescriptionEquals("Sync")
        val pixels = shot()
        assertEquals(0, pixels.countOf(error), "no dot")
        assertEquals(0, pixels.countOf(primary), "not tinted")
        assertTrue(staysStill(), "the idle glyph moved")
    }

    @Test
    fun aRunTurnsTheGlyph() {
        show(SyncBadge.RUNNING)
        button().assertContentDescriptionEquals("Sync, running")
        val shots = List(4) {
            compose.mainClock.advanceTimeBy(SYNC_TURN_MS / 8L)
            frames(1)
            shot()
        }
        for ((a, b) in shots.zipWithNext()) assertFalse(a.contentEquals(b), "the glyph did not turn between two frames an eighth of a turn apart")
        assertEquals(0, shots.first().countOf(error), "no dot while running")
    }

    /** Animations removed: no turn, and the glyph takes the primary colour, so a run still shows. */
    @Test
    fun withAnimationsOffARunIsAStillTintedGlyph() {
        animatorScale(0f)
        show(SyncBadge.RUNNING)
        button().assertContentDescriptionEquals("Sync, running")
        assertTrue(staysStill(), "the glyph turned with animations off")
        val pixels = shot()
        assertTrue(pixels.countOf(primary) >= 40, "the running glyph is not in the primary colour: ${pixels.countOf(primary)} px")
        assertEquals(0, pixels.countOf(error), "no dot while running")
    }

    /** The tint is the cue for animations off only: the turning glyph keeps the plain colour, like the idle one. */
    @Test
    fun withAnimationsOffAnIdleGlyphIsNotTinted() {
        animatorScale(0f)
        show(SyncBadge.IDLE)
        assertEquals(0, shot().countOf(primary))
    }

    @Test
    fun attentionIsARedDot() {
        show(SyncBadge.ATTENTION)
        button().assertContentDescriptionEquals("Sync, needs attention")
        val pixels = shot()
        assertTrue(pixels.countOf(error) >= 40, "no red dot: ${pixels.countOf(error)} px in the error colour")
        assertEquals(0, pixels.countOf(primary), "not tinted")
        assertTrue(staysStill(), "the glyph moved")
    }

    /** One button: the badge changes in place as the run does, and the dot goes once nothing needs the owner. */
    @Test
    fun theBadgeFollowsTheState() {
        show(SyncBadge.ATTENTION)
        badge = SyncBadge.RUNNING
        compose.waitForIdle()
        frames(2)
        button().assertContentDescriptionEquals("Sync, running")
        assertEquals(0, shot().countOf(error))
        badge = SyncBadge.IDLE
        compose.waitForIdle()
        frames(2)
        button().assertContentDescriptionEquals("Sync")
        assertTrue(staysStill(), "the glyph kept turning after the run")
    }

    @Test
    fun aTapOpensSync() {
        show(SyncBadge.IDLE)
        compose.mainClock.autoAdvance = true
        button().performClick()
        assertEquals(1, taps)
    }

    /** No caption: the words are the content description, and a long press shows them as a tooltip, as on the viewer's buttons. */
    @Test
    fun aLongPressShowsTheTooltipAndNoCaptionIsDrawn() {
        show(SyncBadge.ATTENTION)
        compose.mainClock.autoAdvance = true
        compose.onAllNodesWithText("Sync", substring = true).assertCountEquals(0)
        button().performTouchInput { longClick() }
        compose.onNode(hasText("Sync, needs attention"), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, taps, "a long press shows the tooltip; it does not open Sync")
    }
}
