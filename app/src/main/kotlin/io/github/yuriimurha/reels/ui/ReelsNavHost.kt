package io.github.yuriimurha.reels.ui

import android.content.Context
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.yuriimurha.reels.BuildConfig
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.ui.common.LocalNavAnimatedVisibilityScope
import io.github.yuriimurha.reels.ui.common.LocalSharedTransitionScope
import io.github.yuriimurha.reels.ui.common.sharedAxisEnter
import io.github.yuriimurha.reels.ui.common.sharedAxisExit
import io.github.yuriimurha.reels.ui.common.sharedAxisPopEnter
import io.github.yuriimurha.reels.ui.common.sharedAxisPopExit
import io.github.yuriimurha.reels.ui.common.underViewerExit
import io.github.yuriimurha.reels.ui.common.viewerEnter
import io.github.yuriimurha.reels.ui.common.viewerExit
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

private val NavBackStackEntry.isViewer: Boolean get() = destination.hasRoute<ViewerRoute>()

/**
 * [startDestination] is only overridden by tests.
 *
 * Motion (spec 2026-10-12 §3.6, `Motion.kt`): pages change with the shared-axis X transition. A post grows from its grid tile
 * into the viewer and shrinks back into the tile of the post the viewer is on (`sharedMedia`, over the [SharedTransitionLayout]
 * here), while the viewer fades and the page under it stays. Back is predictive (the manifest opts in): the gesture seeks the same
 * pop transitions, and letting go short of Back cancels them. With the system's animations removed, nothing animates.
 */
@Composable
fun ReelsNavHost(navController: NavHostController = rememberNavController(), startDestination: Any = HomeRoute) {
    val context = LocalContext.current
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                enterTransition = { sharedAxisEnter(context) },
                exitTransition = { if (targetState.isViewer) underViewerExit(context) else sharedAxisExit(context) },
                popEnterTransition = { if (initialState.isViewer) EnterTransition.None else sharedAxisPopEnter(context) },
                popExitTransition = { sharedAxisPopExit(context) },
                // The back gesture seeks these same transitions (Navigation's own predictive ones scale the page out instead).
                // A destination's own pop transition does not apply to the gesture, so the viewer's fade is named here too.
                predictivePopEnterTransition = { if (initialState.isViewer) EnterTransition.None else sharedAxisPopEnter(context) },
                predictivePopExitTransition = { if (initialState.isViewer) viewerExit(context) else sharedAxisPopExit(context) },
            ) {
                destinations(navController, context)
            }
        }
    }
}

/**
 * A destination whose content can reach its own enter/exit scope ([LocalNavAnimatedVisibilityScope]), which a shared element
 * follows. [enterTransition] and [exitTransition] serve going back too; null keeps the nav host's.
 */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline enterTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition)? = null,
    noinline exitTransition: (AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition)? = null,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T>(
        enterTransition = enterTransition,
        exitTransition = exitTransition,
        popEnterTransition = enterTransition,
        popExitTransition = exitTransition,
    ) { entry ->
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) { content(entry) }
    }
}

private fun NavGraphBuilder.destinations(navController: NavHostController, context: Context) {
    screen<HomeRoute> {
        HomeScreen(
            onOpenSource = { source, title ->
                navController.navigate(GridRoute(source.encode(), title)) { launchSingleTop = true }
            },
            onOpenSearch = { navController.navigate(SearchRoute) { launchSingleTop = true } },
            onOpenSync = { navController.navigate(SyncRoute) { launchSingleTop = true } },
        )
    }
    screen<GridRoute> { entry ->
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
    // The viewer only fades: the post's shared element carries the motion.
    screen<ViewerRoute>(enterTransition = { viewerEnter(context) }, exitTransition = { viewerExit(context) }) { entry ->
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
    screen<SearchRoute> { entry ->
        val returnedIndex by entry.savedStateHandle.getStateFlow<Int?>(VIEWER_INDEX_KEY, null)
            .collectAsStateWithLifecycle()
        SearchScreen(
            onBack = { navController.popBackSafely() },
            onOpenViewer = { source, index -> navController.navigate(ViewerRoute(source.encode(), index)) { launchSingleTop = true } },
            returnedIndex = returnedIndex,
            onReturnedIndexConsumed = { entry.savedStateHandle.remove<Int>(VIEWER_INDEX_KEY) },
        )
    }
    screen<SyncRoute> {
        SyncScreen(
            onBack = { navController.popBackSafely() },
            onOpenLogin = { url, purpose -> navController.navigate(LoginRoute(url, purpose)) { launchSingleTop = true } },
            onOpenLab = { navController.navigate(AdapterLabRoute) { launchSingleTop = true } },
        )
    }
    if (BuildConfig.DEBUG) {
        screen<AdapterLabRoute> {
            AdapterLabScreen(onBack = { navController.popBackSafely() })
        }
    }
    screen<LoginRoute> { entry ->
        val route = entry.toRoute<LoginRoute>()
        LoginScreen(
            startUrl = route.url,
            onDone = { navController.closeLogin() },
            onBack = { navController.popBackSafely() },
            purpose = route.purpose,
        )
    }
}
