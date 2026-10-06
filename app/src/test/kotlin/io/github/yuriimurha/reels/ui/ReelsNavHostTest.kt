package io.github.yuriimurha.reels.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real [ReelsNavHost] and `GridScreen` over a real (file-backed) Room database, starting on a grid. */
@RunWith(AndroidJUnit4::class)
class ReelsNavHostTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private lateinit var navController: NavHostController
    private val source = MediaSource.Collection(ALL_SAVED_ID)

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        container.db.close()
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
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ReelsNavHost(navController, startDestination = GridRoute(source.encode(), "All Saved"))
                }
            }
        }
        compose.waitForIdle()
    }

    /** Room and Paging load off the main thread, so poll until the grid has composed some tiles. */
    private fun awaitTiles(atLeast: Int) {
        repeat(100) {
            compose.waitForIdle()
            if (compose.onAllNodesWithTag("tile").fetchSemanticsNodes().size >= atLeast) return
            Thread.sleep(50)
        }
        error("grid never showed $atLeast tiles")
    }

    private fun viewersOnTheStack() = navController.currentBackStack.value.count { it.destination.hasRoute<ViewerRoute>() }

    @Test
    fun backOnTheStartDestinationKeepsTheScreen() {
        showGrid()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertNotNull(navController.currentBackStackEntry, "Back emptied the NavHost")
        assertEquals(source, MediaSource.decode(navController.currentBackStackEntry!!.toRoute<GridRoute>().source))
    }

    @Test
    fun aReturnedViewerIndexIsConsumedAfterTheGridScrolledToIt() {
        seed(30)
        showGrid()
        awaitTiles(atLeast = 2)
        compose.onNodeWithText("@author_m9").assertDoesNotExist()
        val gridEntry = navController.currentBackStackEntry!!
        compose.runOnIdle { gridEntry.savedStateHandle[VIEWER_INDEX_KEY] = 20 } // item 20 is m9 (newest first)
        compose.waitForIdle()
        compose.onNodeWithText("@author_m9").assertExists()
        assertNull(gridEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY), "the stale index was left in the handle")
    }

    @Test
    fun aDoubleTapOnATileOpensOneViewer() {
        seed(3)
        showGrid()
        awaitTiles(atLeast = 3)
        compose.mainClock.autoAdvance = false
        val tile = compose.onAllNodesWithTag("tile")[1]
        tile.performClick()
        tile.performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(1, viewersOnTheStack())
    }

    @Test
    fun viewerWritesItsIndexBackAndTheGridConsumesItOnReturn() {
        seed(3)
        showGrid()
        awaitTiles(atLeast = 3)
        val gridEntry = navController.currentBackStackEntry!!
        compose.onAllNodesWithTag("tile")[1].performClick()
        compose.waitForIdle()
        assertEquals(1, navController.currentBackStackEntry!!.toRoute<ViewerRoute>().index)
        assertEquals(1, gridEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY), "the viewer never reported its index")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(gridEntry, navController.currentBackStackEntry)
        assertNull(gridEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY), "the grid left the index behind")
    }

    @Test
    fun searchViewerWritesItsIndexBackAndTheSearchConsumesItOnReturn() {
        seed(30)
        compose.setContent {
            navController = rememberNavController()
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ReelsNavHost(navController, startDestination = SearchRoute)
                }
            }
        }
        compose.waitForIdle()
        val searchEntry = navController.currentBackStackEntry!!
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("searchField").performTextInput("m")
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        awaitTiles(atLeast = 1)
        compose.onAllNodesWithTag("tile")[0].performClick()
        compose.waitForIdle()
        assertEquals(1, viewersOnTheStack())
        val viewerIndex = navController.currentBackStackEntry!!.toRoute<ViewerRoute>().index
        assertEquals(viewerIndex, searchEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY), "the viewer never reported its index")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(searchEntry, navController.currentBackStackEntry)
        assertNull(searchEntry.savedStateHandle.get<Int>(VIEWER_INDEX_KEY), "the search left the index behind")
    }

    @Test
    fun syncOpensTheLoginPageAndBackReturnsToSync() {
        compose.setContent {
            navController = rememberNavController()
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ReelsNavHost(navController, startDestination = SyncRoute)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Log in").performClick()
        compose.waitForIdle()
        assertTrue(navController.currentBackStackEntry!!.destination.hasRoute<LoginRoute>(), "Log in did not open the login page")
        assertNull(navController.currentBackStackEntry!!.toRoute<LoginRoute>().url, "a plain login starts on Instagram's login page")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertTrue(navController.currentBackStackEntry!!.destination.hasRoute<SyncRoute>(), "Back did not return to Sync")
    }
}
