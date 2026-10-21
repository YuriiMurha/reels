package io.github.yuriimurha.reels.ui.common

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

/*
 * The app's motion (spec 2026-10-12 §3.6, as amended by R24). A post grows from its grid tile into the viewer and shrinks back
 * into the tile of the post the viewer is on (a shared element, [sharedMedia]); every other page change is a push, as in
 * Instagram: the new page slides in over the old one, which drifts a third of the way out under it, and Back is the reverse.
 * Only the viewer fades, and its text and controls stay above the moving post ([aboveSharedMedia], R30). When the system's
 * animations are removed (animator duration scale 0), nothing animates.
 */

/** False when the system's animator duration scale is 0 (Remove animations): then no page transition and no shared element. */
fun motionEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** The viewer's own fade, in and out. */
private const val FADE_MS = 300

/** A page push, forward or back. */
private const val PAGE_MS = 300

/**
 * A push's timing: decelerating for a tap or a Back press ([FastOutSlowInEasing]); linear under the back gesture, which seeks
 * the transition by the gesture's progress, so the page moves in step with the finger rather than ahead of it or behind.
 */
private fun pageSpec(gesture: Boolean): FiniteAnimationSpec<IntOffset> =
    tween(PAGE_MS, easing = if (gesture) LinearEasing else FastOutSlowInEasing)

/** Push, forward: the new page slides in from the end edge, its whole width, over the page left. No fade. */
fun pageEnter(context: Context): EnterTransition =
    if (motionEnabled(context)) slideInHorizontally(pageSpec(gesture = false)) { it } else EnterTransition.None

/** Push, forward: the page left drifts a third of its width towards the start, under the new one. No fade. */
fun pageExit(context: Context): ExitTransition =
    if (motionEnabled(context)) slideOutHorizontally(pageSpec(gesture = false)) { -it / 3 } else ExitTransition.None

/**
 * Push, back: the reverse of [pageExit], the previous page comes back from a third of its width towards the start, under the
 * page closed. [gesture]: the back gesture seeks it (linear timing).
 */
fun pagePopEnter(context: Context, gesture: Boolean = false): EnterTransition =
    if (motionEnabled(context)) slideInHorizontally(pageSpec(gesture)) { -it / 3 } else EnterTransition.None

/**
 * Push, back: the reverse of [pageEnter], the page closed slides out to the end edge, its whole width, over the previous one.
 * [gesture]: the back gesture seeks it (linear timing).
 */
fun pagePopExit(context: Context, gesture: Boolean = false): ExitTransition =
    if (motionEnabled(context)) slideOutHorizontally(pageSpec(gesture)) { it } else ExitTransition.None

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

/** The viewer's overlay layer's z-index in the shared transition's overlay ([aboveSharedMedia]): above the media's, the default 0. */
private const val ABOVE_MEDIA_Z_INDEX = 1f

/**
 * Draws this, the viewer's overlay layer (its text and controls), above the post's media at every moment of the grow and the
 * shrink (R30). While the post moves, [sharedMedia] draws its media in the [SharedTransitionLayout]'s overlay, above everything
 * laid out in the layout, the viewer's page with its controls included. So this layer goes into that overlay too, above the
 * media, for as long as the viewer's own transition runs (it fades in or out, or the back gesture seeks it): the media's bounds
 * animate on that same transition, which lasts until they have settled.
 *
 * Drawn from the overlay it no longer takes the fade of the page it sits on, so it fades itself, with the viewer's own fade
 * ([viewerEnter], [viewerExit], on the destination's transition): at every frame it is as faded as its page, also when no
 * media moves (a late first page), rather than taking the page's fade and its own. Does nothing outside a shared transition
 * (previews, screen tests).
 */
@Composable
fun Modifier.aboveSharedMedia(): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    val context = LocalContext.current
    val transition = visibility.transition
    val layer = with(shared) {
        this@aboveSharedMedia.renderInSharedTransitionScopeOverlay(
            zIndexInOverlay = ABOVE_MEDIA_Z_INDEX,
            renderInOverlay = { transition.currentState != transition.targetState },
        )
    }
    return with(visibility) { layer.animateEnterExit(enter = viewerEnter(context), exit = viewerExit(context)) }
}
