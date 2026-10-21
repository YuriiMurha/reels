package io.github.yuriimurha.reels.ui.home

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.di.Backend
import io.github.yuriimurha.reels.di.BackendChoice
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.testutil.closingAfterComposition
import io.github.yuriimurha.reels.ui.HomeRoute
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.ReelsNavHost
import io.github.yuriimurha.reels.ui.SearchRoute
import io.github.yuriimurha.reels.ui.SyncRoute
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home's top bar in the real [ReelsNavHost] (2026-10-21): the search bar opens Search with its field focused (so the keyboard is
 * up), the sync button opens Sync, and the button's badge reads the real library's session but not Mock mode's.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    val compose = createComposeRule()

    /** The databases close only once the compose rule has disposed the screen: its flows query them until then. */
    @get:Rule
    val rules = closingAfterComposition(compose) {
        if (::container.isInitialized) {
            container.requestLogDb.close()
            container.db.close()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private lateinit var navController: NavHostController

    /** The app from Home, on the fake library (Mock mode, a debug build's default) or the real one. */
    private fun showHome(useFake: Boolean = true) {
        context.getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(BackendChoice.KEY_USE_FAKE, useFake).commit()
        container = AppContainer(context)
        assertTrue((container.backend is Backend.Fake) == useFake, "the container runs the library asked for")
        compose.setContent {
            navController = rememberNavController()
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ReelsNavHost(navController, startDestination = HomeRoute)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun onRoute(check: (NavDestination) -> Boolean) = check(navController.currentBackStackEntry!!.destination)

    /** Room and DataStore load off the main thread: poll until [condition] holds. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        repeat(200) {
            compose.waitForIdle()
            if (condition()) return
            Thread.sleep(25)
        }
        error("never: $what")
    }

    @Test
    fun theSearchBarOpensSearchWithItsFieldFocused() {
        showHome()
        compose.onNodeWithTag(HOME_SEARCH_TAG).performClick()
        compose.waitForIdle()
        assertTrue(onRoute { it.hasRoute<SearchRoute>() }, "the search bar did not open Search")
        compose.onNode(hasTestTag("searchField")).assertIsFocused()
    }

    @Test
    fun theSyncButtonOpensSync() {
        showHome()
        compose.onNodeWithTag(SYNC_BUTTON_TAG).performClick()
        compose.waitForIdle()
        assertTrue(onRoute { it.hasRoute<SyncRoute>() }, "the sync button did not open Sync")
    }

    /**
     * Mock mode's fake library needs no login: a fresh install shows the plain glyph, though the stored session (shared by both
     * libraries) is logged out. The session is read first, so a badge that wrongly followed it would have had time to change.
     */
    @Test
    fun mockModeShowsNoDotForALoggedOutSession() {
        showHome(useFake = true)
        assertEquals(SessionState.LoggedOut, runBlocking { container.session.state.first() })
        awaitUntil("Home's empty state") { compose.onAllNodes(hasText("Nothing synced yet")).fetchSemanticsNodes().isNotEmpty() }
        repeat(20) {
            compose.waitForIdle()
            assertEquals("Sync", label())
            Thread.sleep(25)
        }
    }

    /** The real library, logged out (a fresh install): Sync stays off until the owner logs in, so the button asks for attention. */
    @Test
    fun aLoggedOutRealLibraryPutsADotOnTheSyncButton() {
        showHome(useFake = false)
        awaitUntil("the dot, after ${label()}") { label() == "Sync, needs attention" }
    }

    private fun label(): String? = compose.onNodeWithTag(SYNC_BUTTON_TAG).fetchSemanticsNode().config
        .getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.singleOrNull()
}
