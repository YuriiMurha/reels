package io.github.yuriimurha.reels.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.yuriimurha.reels.BuildConfig
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.ui.grid.GridScreen
import io.github.yuriimurha.reels.ui.home.HomeScreen
import io.github.yuriimurha.reels.ui.lab.AdapterLabScreen
import io.github.yuriimurha.reels.ui.login.LoginPurpose
import io.github.yuriimurha.reels.ui.login.LoginScreen
import io.github.yuriimurha.reels.ui.search.SearchScreen
import io.github.yuriimurha.reels.ui.sync.SyncScreen
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

/** The debug-only Adapter lab. Registered, and reachable from Sync's Developer section, in debug builds only. */
@Serializable
data object AdapterLabRoute

/**
 * [url] is where the WebView starts (a challenge page, or Instagram's home after a paste); null means the login page.
 * [purpose] decides what the screen may spend an Instagram request on.
 */
@Serializable
data class LoginRoute(val url: String? = null, val purpose: LoginPurpose = LoginPurpose.LOGIN)

/** The viewer writes its current index here on the previous entry, so the grid scrolls back to it. */
const val VIEWER_INDEX_KEY = "viewerIndex"

/** Pops only when there is somewhere to go back to, so a stray Back can't empty the NavHost (blank screen). */
fun NavHostController.popBackSafely() {
    if (previousBackStackEntry != null) popBackStack()
}

/** Closes the login screen and nothing else: a late result arriving after it is gone must not also pop Sync. */
fun NavHostController.closeLogin() {
    popBackStack<LoginRoute>(inclusive = true)
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
                onOpenSearch = { navController.navigate(SearchRoute) { launchSingleTop = true } },
                onOpenSync = { navController.navigate(SyncRoute) { launchSingleTop = true } },
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
        composable<SearchRoute> { entry ->
            val returnedIndex by entry.savedStateHandle.getStateFlow<Int?>(VIEWER_INDEX_KEY, null)
                .collectAsStateWithLifecycle()
            SearchScreen(
                onBack = { navController.popBackSafely() },
                onOpenViewer = { source, index -> navController.navigate(ViewerRoute(source.encode(), index)) { launchSingleTop = true } },
                returnedIndex = returnedIndex,
                onReturnedIndexConsumed = { entry.savedStateHandle.remove<Int>(VIEWER_INDEX_KEY) },
            )
        }
        composable<SyncRoute> {
            SyncScreen(
                onBack = { navController.popBackSafely() },
                onOpenLogin = { url, purpose -> navController.navigate(LoginRoute(url, purpose)) { launchSingleTop = true } },
                onOpenLab = { navController.navigate(AdapterLabRoute) { launchSingleTop = true } },
            )
        }
        if (BuildConfig.DEBUG) {
            composable<AdapterLabRoute> {
                AdapterLabScreen(onBack = { navController.popBackSafely() })
            }
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            LoginScreen(
                startUrl = route.url,
                onDone = { navController.closeLogin() },
                onBack = { navController.popBackSafely() },
                purpose = route.purpose,
            )
        }
    }
}
