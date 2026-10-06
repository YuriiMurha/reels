package io.github.yuriimurha.reels.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.ui.grid.GridScreen
import io.github.yuriimurha.reels.ui.home.HomeScreen
import io.github.yuriimurha.reels.ui.viewer.ViewerScreen
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data class GridRoute(val source: String, val title: String)

@Serializable
data class ViewerRoute(val source: String, val index: Int)

@Serializable
data object SearchRoute

@Serializable
data object SyncRoute

/** The viewer writes its current index here on the previous entry, so the grid scrolls back to it. */
const val VIEWER_INDEX_KEY = "viewerIndex"

/** Pops only when there is somewhere to go back to, so a stray Back can't empty the NavHost (blank screen). */
fun NavHostController.popBackSafely() {
    if (previousBackStackEntry != null) popBackStack()
}

/** [startDestination] is only overridden by tests. */
@Composable
fun ReelsNavHost(navController: NavHostController = rememberNavController(), startDestination: Any = HomeRoute) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenSource = { source, title ->
                    navController.navigate(GridRoute(source.encode(), title)) { launchSingleTop = true }
                },
                onOpenSearch = {},
                onOpenSync = {},
            )
        }
        composable<GridRoute> { entry ->
            val route = entry.toRoute<GridRoute>()
            val returnedIndex by entry.savedStateHandle.getStateFlow<Int?>(VIEWER_INDEX_KEY, null)
                .collectAsStateWithLifecycle()
            GridScreen(
                source = MediaSource.decode(route.source),
                title = route.title,
                returnedIndex = returnedIndex,
                onBack = { navController.popBackSafely() },
                onOpenViewer = { index ->
                    navController.navigate(ViewerRoute(route.source, index)) { launchSingleTop = true }
                },
                onReturnedIndexConsumed = { entry.savedStateHandle.remove<Int>(VIEWER_INDEX_KEY) },
            )
        }
        composable<ViewerRoute> { entry ->
            val route = entry.toRoute<ViewerRoute>()
            ViewerScreen(
                source = MediaSource.decode(route.source),
                startIndex = route.index,
                onIndexSettled = { index ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(VIEWER_INDEX_KEY, index)
                },
                onBack = { navController.popBackSafely() },
            )
        }
    }
}
