package io.github.yuriimurha.reels.ui.common

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/*
 * The app's motion (spec 2026-10-12 §3.6). A post grows from its grid tile into the viewer and shrinks back into the tile of
 * the post the viewer is on (a shared element, [sharedMedia]); every other page change is the Material shared-axis X
 * transition. When the system's animations are removed (animator duration scale 0), nothing animates.
 */

/** False when the system's animator duration scale is 0 (Remove animations): then no page transition and no shared element. */
fun motionEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** The fade of every page transition, and of the viewer's own. */
private const val FADE_MS = 300

/** Shared-axis X, forward: the new page slides in from the end, with a fade. */
fun sharedAxisEnter(context: Context): EnterTransition =
    if (motionEnabled(context)) slideInHorizontally { it / 10 } + fadeIn(tween(FADE_MS)) else EnterTransition.None

/** Shared-axis X, forward: the page being left slides out to the start, with a fade. */
fun sharedAxisExit(context: Context): ExitTransition =
    if (motionEnabled(context)) slideOutHorizontally { -it / 10 } + fadeOut(tween(FADE_MS)) else ExitTransition.None

/** Shared-axis X, back: the reverse of [sharedAxisExit], the previous page slides back in from the start. */
fun sharedAxisPopEnter(context: Context): EnterTransition =
    if (motionEnabled(context)) slideInHorizontally { -it / 10 } + fadeIn(tween(FADE_MS)) else EnterTransition.None

/** Shared-axis X, back: the reverse of [sharedAxisEnter], the page being closed slides out to the end. */
fun sharedAxisPopExit(context: Context): ExitTransition =
    if (motionEnabled(context)) slideOutHorizontally { it / 10 } + fadeOut(tween(FADE_MS)) else ExitTransition.None

/** The viewer only fades in, and out: the post's shared element ([sharedMedia]) carries the motion. */
fun viewerEnter(context: Context): EnterTransition = if (motionEnabled(context)) fadeIn(tween(FADE_MS)) else EnterTransition.None

/** See [viewerEnter]. */
fun viewerExit(context: Context): ExitTransition = if (motionEnabled(context)) fadeOut(tween(FADE_MS)) else ExitTransition.None

/**
 * The page under an opening viewer (a grid, or the search results) stays as it is until the viewer has faded in over it: its
 * tile is where the post grows from, and a page sliding or fading away under it would show the window behind. Going back, the
 * page is simply there under the fading viewer ([EnterTransition.None]).
 */
fun AnimatedContentTransitionScope<*>.underViewerExit(context: Context): ExitTransition =
    if (motionEnabled(context)) ExitTransition.KeepUntilTransitionsFinished else ExitTransition.None

/** The [SharedTransitionLayout] around the nav host; null outside it (previews and screen tests), where [sharedMedia] does nothing. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The enter/exit scope of the nav destination around the content, which a shared element follows; null outside one. */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** The key a post's media shares between its grid tile and the viewer. */
internal fun sharedMediaKey(pk: String): String = "media-$pk"

/**
 * Marks this as post [pk]'s media, shared between its grid tile and the viewer: opening the post grows the tile into the viewer,
 * and going back shrinks it into the tile. Only one element per screen may carry a post's key (the viewer marks its settled page
 * only). Does nothing outside a shared transition (previews, screen tests) and when animations are off.
 *
 * Shared bounds, each side scaled into them (not laid out again at every size): the tile crops its picture and the viewer fits
 * it, and the post on screen may be a playing video or another carousel item than the tile's cover. Scaled to the bounds'
 * width, both pictures land on the same place, and the two cross-fade, so neither side jumps; a video's surface is never
 * resized mid-transition.
 */
@Composable
fun Modifier.sharedMedia(pk: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    if (!motionEnabled(LocalContext.current)) return this
    return with(shared) {
        this@sharedMedia.sharedBounds(
            rememberSharedContentState(sharedMediaKey(pk)),
            visibility,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}
