package io.github.yuriimurha.reels.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.TypeFilter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** `MediaSource.encode()` is raw JSON (braces, quotes, slashes, colons, spaces) and travels as a route String. */
@RunWith(AndroidJUnit4::class)
class RouteEncodingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rawJsonSourceAndAwkwardTitleSurviveARouteRoundTrip() {
        val source = MediaSource.Search("a\"b/c d*", TypeFilter.REELS, "c/1 ?&=")
        val title = "t / ? # %"
        var capturedSource: MediaSource? = null
        var capturedTitle: String? = null
        compose.setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = HomeRoute) {
                composable<HomeRoute> { }
                composable<GridRoute> { entry ->
                    val route = entry.toRoute<GridRoute>()
                    capturedSource = MediaSource.decode(route.source)
                    capturedTitle = route.title
                }
            }
            LaunchedEffect(Unit) {
                navController.navigate(GridRoute(source.encode(), title))
            }
        }
        compose.waitForIdle()
        assertNotNull(capturedSource, "the grid destination never composed")
        assertEquals(source, capturedSource)
        assertEquals(title, capturedTitle)
    }
}
