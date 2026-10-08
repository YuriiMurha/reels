package io.github.yuriimurha.reels

import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.ui.viewer.VIDEO_SURFACE_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * On-device smoke tests: the app driven through its real UI, like a browser test for the phone app. Basics only.
 *
 * Safety (see [SmokeGuard]): every test skips on a physical phone and fails unless the app is in Mock mode, so nothing here
 * can reach Instagram. Nothing taps Log in, Log in again, Paste sessionid, Resolve on Instagram, Check now, Open on
 * Instagram, the Adapter lab or the Mock mode switch: those are only checked for presence.
 *
 * The tests do not depend on each other (each starts a fresh Activity on Home, and syncs the fake library first if it is
 * empty); [FixMethodOrder] only makes the screenshots come out in the order of the story.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeTest {
    // The v1 rule on purpose: v2 runs the composition's coroutines on the test thread, and the viewer's ExoPlayer insists on the main thread.
    @Suppress("DEPRECATION")
    private val composeRule = createAndroidComposeRule<MainActivity>()

    // Outermost first: nothing is launched or granted on a physical phone, or with Mock mode off.
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(SmokeGuard()).around(notificationPermissionRule()).around(composeRule)

    /** A second line of defence behind [SmokeGuard]: every test begins with the emulator assumption and the Mock mode check. */
    @Before
    fun guard() = requireSafeTarget()

    /** Whatever the test did in the app, Mock mode built no WebView transport: nothing here ever loaded or asked Instagram. */
    @After
    fun noWebViewTransportWasBuilt() = requireNoTransport()

    // --- 1. Launch -------------------------------------------------------------------------------------------------------

    @Test
    fun t1Launch_homeShowsTitleSearchAndSyncChip() {
        val shots = Screenshots(composeRule, "t1")
        await("the Home top bar") { exists(homeTitle) }
        composeRule.onNode(homeTitle).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search").assertIsDisplayed()
        composeRule.onNode(syncChip).assertIsDisplayed()
        shots.take("home")
    }

    // --- 2. Sync screen, then a sync -------------------------------------------------------------------------------------

    @Test
    fun t2Sync_sessionIsLoggedOutDeveloperShowsMockModeAndSyncFillsHome() {
        val shots = Screenshots(composeRule, "t2")
        await("the Home top bar") { exists(homeTitle) }
        openSync()
        shots.take("sync-screen")

        // Session: logged out, with the Log in button present. NEVER clicked.
        composeRule.onNodeWithText("Instagram session").assertIsDisplayed()
        await("the session to read as logged out (an emulator must never be logged in)") { exists(hasText("Not logged in")) }
        composeRule.onNode(hasText("Log in") and hasClickAction()).assertIsDisplayed().assertIsEnabled().assertHasClickAction()

        // Developer: debug build, Mock mode on. NEVER toggled.
        assertTrue("the smoke tests need a debug build", BuildConfig.DEBUG)
        composeRule.onNodeWithText("Developer").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Mock mode (fake library)").performScrollTo().assertIsDisplayed().assertIsOn()
        composeRule.onNodeWithText("Adapter lab").assertExists() // not tapped
        shots.take("developer-mock-mode-on")

        syncToCompletion()
        await("the Sync screen to show the finished run") { exists(hasText("Last run")) } // the screen lags the database
        composeRule.onNodeWithText("Last run").performScrollTo().assertIsDisplayed()
        shots.take("sync-finished")

        pressBackUntil("Home") { exists(homeTitle) && exists(card("All Saved")) }
        composeRule.onNode(card("All Saved")).assertIsDisplayed()
        shots.take("home-after-sync")
    }

    // --- 3. Grid and viewer ----------------------------------------------------------------------------------------------

    @Test
    fun t3GridAndViewer_openATileSeeViewerControlsAndBackToTheGrid() {
        val shots = Screenshots(composeRule, "t3")
        ensureLibrary()
        openCard("All Saved")
        shots.take("grid")

        composeRule.onAllNodes(tile)[0].assertIsDisplayed().performClick()
        await("the viewer's Open on Instagram") { displayed(openOnInstagram).isNotEmpty() }
        displayed(openOnInstagram).first().assertIsDisplayed() // NEVER clicked
        shots.take("viewer")

        // Mute only exists on a video page; the first tile may be a photo, so swipe on to the first video.
        swipeToVideoPage()
        displayed(muteControl).first().assertIsDisplayed() // NEVER clicked
        displayed(openOnInstagram).first().assertIsDisplayed()
        shots.take("viewer-video-page")

        pressBackUntil("the grid again") { exists(gridTitle("All Saved")) && exists(tile) && !exists(openOnInstagram) }
        composeRule.onAllNodes(tile)[0].assertIsDisplayed()
        shots.take("grid-again")
    }

    // --- 4. Video --------------------------------------------------------------------------------------------------------

    /**
     * The viewer gives the test no player to ask, so playback is read where it shows: the page that has the player carries
     * the [VIDEO_SURFACE_TAG] test tag (the only production change), and the player draws on a TextureView, whose bitmap is
     * empty until the first frame arrives and then changes as the clip (colour bars with a moving sweep and a running
     * timecode) advances. "Started" means: a frame was drawn, and a later frame differs from it.
     */
    @Test
    fun t4Video_aReelPageStartsPlaying() {
        val shots = Screenshots(composeRule, "t4")
        ensureLibrary()
        openCard("All Saved")
        composeRule.onAllNodes(tile)[0].performClick()
        await("the viewer's Open on Instagram") { displayed(openOnInstagram).isNotEmpty() }
        swipeToVideoPage()

        await("the video surface of the reel page", timeoutMs = VIDEO_TIMEOUT_MS) { exists(hasTestTag(VIDEO_SURFACE_TAG)) }
        composeRule.onNodeWithTag(VIDEO_SURFACE_TAG).assertIsDisplayed()

        val surface = checkNotNull(findTextureView()) { "the video surface exists, but no TextureView is attached under it" }
        var first: IntArray? = null
        await("a first video frame on the TextureView", timeoutMs = VIDEO_TIMEOUT_MS) {
            first = sampleFrame(surface)?.takeIf(::looksLikeVideo)
            first != null
        }
        shots.take("video-playing")
        await("the video to advance to another frame (it is not stuck on one)", timeoutMs = VIDEO_TIMEOUT_MS) {
            sampleFrame(surface)?.let { it.size == first!!.size && !it.contentEquals(first!!) } == true
        }
        displayed(muteControl).first().assertIsDisplayed()
    }

    // --- 5. Search -------------------------------------------------------------------------------------------------------

    @Test
    fun t5Search_aCaptionWordFindsResultsAndBackReturnsHome() {
        val shots = Screenshots(composeRule, "t5")
        ensureLibrary()
        composeRule.onNodeWithContentDescription("Search").performClick()
        await("the search field") { exists(hasTestTag("searchField")) }
        shots.take("search-empty")

        // A word the fake library's captions are made of (FakeLibrary.WORDS): about one item in five has it.
        composeRule.onNodeWithTag("searchField").performTextInput(SEARCH_WORD)
        await("search results for \"$SEARCH_WORD\"") { exists(tileWith(SEARCH_WORD)) }
        composeRule.onAllNodes(tileWith(SEARCH_WORD))[0].assertIsDisplayed()
        shots.take("search-results")

        // The first Back may only close the keyboard.
        pressBackUntil("Home") { exists(homeTitle) && exists(card("All Saved")) && !exists(hasTestTag("searchField")) }
        shots.take("home-after-search")
    }

    // --- Steps -----------------------------------------------------------------------------------------------------------

    /** Home shows cards, or, when the fake library is empty, a sync fills it first. */
    private fun ensureLibrary() {
        await("Home to show its cards or its empty state") { exists(anyCard) || exists(hasText("Nothing synced yet")) }
        if (exists(anyCard)) return
        openSync()
        syncToCompletion()
        pressBackUntil("Home") { exists(homeTitle) && exists(anyCard) }
    }

    private fun openSync() {
        composeRule.onNode(syncChip).performClick()
        await("the Sync screen") { exists(hasText("Instagram session")) }
    }

    /**
     * Taps Sync (or Resume, which replaces Sync and Full sync while a run is resumable) and waits for the run to end.
     * A run that is already going is just waited for.
     */
    private fun syncToCompletion() {
        val sync = hasText("Sync") and hasClickAction() and isEnabled() // the top bar title "Sync" has no click action
        val resume = hasText("Resume") and hasClickAction() and isEnabled()
        val startedAt = System.currentTimeMillis()
        if (latestRun()?.status != SyncStatus.RUNNING) {
            when {
                exists(sync) -> composeRule.onNode(sync).performScrollTo().performClick()
                exists(resume) -> composeRule.onNode(resume).performScrollTo().performClick()
                else -> fail("Neither Sync nor Resume is enabled; the latest run is ${latestRun()}")
            }
        }
        await("the sync run to finish", timeoutMs = SYNC_TIMEOUT_MS) {
            val run = latestRun()
            when {
                run == null || run.status == SyncStatus.RUNNING -> false
                run.status == SyncStatus.DONE -> (run.finishedAt ?: 0L) >= startedAt || run.startedAt >= startedAt
                else -> throw AssertionError("The fake sync stopped as ${run.status}: ${run.lastError}")
            }
        }
    }

    private fun latestRun(): SyncRunEntity? = runBlocking { app.container.db.syncDao().latestRun() }

    private val app: ReelsApp
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ReelsApp

    /** Opens the Home card called [name] and waits for its grid to show tiles. */
    private fun openCard(name: String) {
        await("the \"$name\" card") { exists(card(name)) }
        composeRule.onNode(card(name)).performClick()
        await("the grid of \"$name\"") { exists(gridTitle(name)) && exists(tile) }
    }

    /** Swipes the viewer up, one page at a time, until the settled page is a video (it has a Mute or Unmute control). */
    private fun swipeToVideoPage() {
        repeat(MAX_SWIPES) {
            if (displayed(muteControl).isNotEmpty()) return
            composeRule.onRoot().performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 250) }
            composeRule.waitForIdle()
        }
        await("a video page within $MAX_SWIPES swipes") { displayed(muteControl).isNotEmpty() }
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()
    }

    /** Presses Back until [condition] holds (a Back can be spent closing the keyboard), at most three times. */
    private fun pressBackUntil(what: String, condition: () -> Boolean) {
        repeat(3) {
            pressBack()
            if (settles(BACK_TIMEOUT_MS, condition)) return
        }
        fail("Back did not lead to $what")
    }

    // --- Matchers and waiting --------------------------------------------------------------------------------------------

    private val homeTitle = hasText("Saved")
    private val anyCard = hasTestTag("collection-name")
    private fun card(name: String) = hasTestTag("collection-name") and hasText(name, substring = true)
    private val tile = hasTestTag("tile") and isEnabled()
    private fun tileWith(word: String) = hasTestTag("tile") and hasText(word, substring = true, ignoreCase = true)

    /** A grid's top bar title, which is not the Home card of the same name (that one has a test tag). */
    private fun gridTitle(name: String) = hasText(name) and !hasTestTag("collection-name") and !hasClickAction()
    private val syncChip = hasClickAction() and
        (hasText("Not synced") or hasText("Syncing…") or hasText("Synced ", substring = true) or hasText("⚠", substring = true))
    private val openOnInstagram = hasText("Open on Instagram")
    private val muteControl = hasText("Mute") or hasText("Unmute")

    private fun exists(matcher: SemanticsMatcher): Boolean = composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** The nodes matching [matcher] that are on screen: the viewer composes its neighbour pages too, and they match as well. */
    private fun displayed(matcher: SemanticsMatcher): List<SemanticsNodeInteraction> {
        val all = composeRule.onAllNodes(matcher)
        return (0 until all.fetchSemanticsNodes().size).map { all[it] }.filter { runCatching { it.isDisplayed() }.getOrDefault(false) }
    }

    private fun await(what: String, timeoutMs: Long = UI_TIMEOUT_MS, condition: () -> Boolean) {
        composeRule.waitUntil("$what (within ${timeoutMs / 1_000} s)", timeoutMs, condition)
    }

    /** Like [await], but answers false instead of failing. */
    private fun settles(timeoutMs: Long, condition: () -> Boolean): Boolean =
        try {
            composeRule.waitUntil("a condition", timeoutMs, condition)
            true
        } catch (e: ComposeTimeoutException) {
            false
        }

    // --- Video frames ----------------------------------------------------------------------------------------------------

    private fun findTextureView(): TextureView? {
        var found: TextureView? = null
        composeRule.runOnUiThread { found = textureViewIn(composeRule.activity.window.decorView) }
        return found
    }

    private fun textureViewIn(view: View): TextureView? =
        when (view) {
            is TextureView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { textureViewIn(view.getChildAt(it)) }
            else -> null
        }

    /** A small copy of what the surface has drawn so far, or null when it has no surface yet. */
    private fun sampleFrame(surface: TextureView): IntArray? {
        var pixels: IntArray? = null
        composeRule.runOnUiThread {
            if (surface.isAvailable) {
                surface.getBitmap(FRAME_WIDTH, FRAME_HEIGHT)?.let { bitmap ->
                    pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT).also { bitmap.getPixels(it, 0, FRAME_WIDTH, 0, 0, FRAME_WIDTH, FRAME_HEIGHT) }
                    bitmap.recycle()
                }
            }
        }
        return pixels
    }

    /** An empty surface is one transparent colour; the clip's colour bars are many opaque ones. */
    private fun looksLikeVideo(pixels: IntArray): Boolean = pixels.filter { it ushr 24 != 0 }.distinct().size >= MIN_VIDEO_COLOURS

    private companion object {
        const val UI_TIMEOUT_MS = 30_000L
        const val BACK_TIMEOUT_MS = 4_000L
        const val VIDEO_TIMEOUT_MS = 30_000L
        const val SYNC_TIMEOUT_MS = 180_000L
        const val MAX_SWIPES = 12
        const val SEARCH_WORD = "coffee"
        const val FRAME_WIDTH = 54
        const val FRAME_HEIGHT = 96
        const val MIN_VIDEO_COLOURS = 4
    }
}
