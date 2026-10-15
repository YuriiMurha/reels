package io.github.yuriimurha.reels.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.yuriimurha.reels.R
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.data.media.videoMediaSourceFactory
import io.github.yuriimurha.reels.data.media.viewerLoadControl
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Permalinks
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.Thumbnail
import io.github.yuriimurha.reels.ui.common.sharedMedia
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/** Test tag of the page that has the player (only the settled, playing page has one); the on-device smoke suite looks for it. */
const val VIDEO_SURFACE_TAG = "video-surface"

/** Test tag of a page's media (the box the shared element goes on, see `PostMedia`). */
const val POST_MEDIA_TAG = "post-media"

/** The player stopped on the unit of [page] with [error]; a new instance per failure, so the effect that handles it restarts. */
private class PlayerFailure(val page: SettledPage, val error: Throwable)

/** For a carousel post, the item its pager settled on in the [carousel] visit (0 until the visit says otherwise); null for any other post. */
private fun carouselPosition(media: MediaEntity, carousel: SettledCarousel?): Int? =
    if (media.type != MediaType.CAROUSEL) null else carousel?.takeIf { it.pk == media.pk }?.state?.settled ?: 0

/** Full-screen vertical pager over the same list the grid showed (spec 9.3). One player is reused across pages. */
@Composable
fun ViewerScreen(source: MediaSource, startIndex: Int, onIndexSettled: (Int) -> Unit, onBack: () -> Unit) {
    ViewerScreen(source, startIndex, onIndexSettled, onBack, rememberViewerPlayer(LocalAppContainer.current))
}

/** The viewer over [player], the one player every page shares; a test passes one it can watch. */
@Composable
internal fun ViewerScreen(source: MediaSource, startIndex: Int, onIndexSettled: (Int) -> Unit, onBack: () -> Unit, player: Player) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel = viewModel(key = "viewer:${source.encode()}:$startIndex") {
        ViewerViewModel(source, startIndex, container.library, container.videoResolver, container.settings, container.carouselRepository)
    }
    val items = viewModel.items.collectAsLazyPagingItems()
    val muted by viewModel.muted.collectAsStateWithLifecycle(initialValue = false)
    /** The settled post's carousel visit (its items and settled item), or null when that post is not a carousel. */
    val carousel by viewModel.carousel.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = startIndex) { items.itemCount }
    /** The unit the player has loaded (its page, and for a carousel its item), or null. */
    var playing by remember { mutableStateOf<SettledPage?>(null) }
    /** The unit that cannot play, with the reason shown over it. */
    var unavailable by remember { mutableStateOf<Pair<Playable, String>?>(null) }
    var failure by remember { mutableStateOf<PlayerFailure?>(null) }

    /**
     * The settled unit now: the vertical pager's post and, for a carousel, the item its own pager settled on. Null while the
     * list and the pager's layout disagree (C1, [settledPageOrNull]): the instant a list moves under the settled post.
     */
    val settledNow = {
        val index = pagerState.settledPage
        val media = items.itemSnapshotList.getOrNull(index)
        val laidOut = pagerState.layoutInfo.visiblePagesInfo.firstOrNull { it.index == index }?.key
        settledPageOrNull(index, media, laidOut, media?.let { carouselPosition(it, carousel) })
    }
    val isStillSettled = { unit: Playable -> settledNow()?.playable == unit }
    val present = { page: SettledPage, source: VideoSource ->
        when (source) {
            is VideoSource.Play -> {
                player.setMediaItem(source.toMediaItem())
                player.prepare()
                player.play()
                unavailable = null
                playing = page
            }
            is VideoSource.Unavailable -> {
                player.stop()
                player.clearMediaItems()
                playing = null
                unavailable = page.playable?.let { it to source.message }
            }
        }
    }

    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playing?.let { failure = PlayerFailure(it, error) }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // Both effects below call the player, which must only ever be called on the main thread ([onMainThread]).
    LaunchedEffect(pagerState, items) {
        onMainThread {
            viewModel.followSettledPages(
                // A new post, or a new item of the settled carousel (its pager reports it to the ViewModel, whose state this reads).
                // The list moving under the post then arrives as that post at its new index only: a re-settle in place.
                settled = snapshotFlow(settledNow)
                    .filterNotNull()
                    .distinctUntilChanged { a, b -> a.index == b.index && a.media?.pk == b.media?.pk && a.position == b.position },
                nextOf = { page -> items.itemSnapshotList.getOrNull(page.index + 1) },
                isStillSettled = isStillSettled,
                onSettled = { page ->
                    onIndexSettled(page.index)
                    player.stop()
                    player.clearMediaItems()
                    playing = null
                    unavailable = null
                    failure = null
                },
                onSource = present,
            )
        }
    }
    // A refused link is renewed once; the effect restarts for every new failure and is cancelled by the next settle (failure = null).
    LaunchedEffect(failure) {
        val failed = failure ?: return@LaunchedEffect
        val media = failed.page.media ?: return@LaunchedEffect
        onMainThread {
            playIfStillSettled(
                Playable(media.pk, failed.page.position),
                resolve = { viewModel.recover(media, failed.error, it.position) },
                isStillSettled = isStillSettled,
                onSource = { present(failed.page, it) },
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            key = items.itemKey { it.pk },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val media = items[page]
            val collections by produceState(emptyList<String>(), media?.pk) {
                value = media?.let { viewModel.collectionNames(it.pk) }.orEmpty()
            }
            /** This page's unit: its post, and for the settled carousel its settled item. */
            val unit = media?.let { Playable(it.pk, carouselPosition(it, carousel)) }
            val cannotPlay = unavailable?.takeIf { unit != null && it.first == unit }?.second
            val carouselState = media?.let { m -> carousel?.takeIf { it.pk == m.pk }?.state }
            // R29: a carousel's row whose cover changed in place (a sync's drop, or its new cover) is no settle; the visit takes it.
            if (media?.type == MediaType.CAROUSEL) LaunchedEffect(media.pk, media.thumbPath) { viewModel.onRowChanged(media) }
            ViewerPage(
                media = media,
                player = player.takeIf { unit != null && playing?.playable == unit },
                collections = collections,
                muted = muted,
                onToggleMute = viewModel::toggleMute,
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                onOpenInstagram = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                // A carousel item's reason shows on that item; any other post's above Open on Instagram (spec 8.5).
                unavailable = cannotPlay?.takeIf { unit?.position == null },
                carousel = carouselState?.let { state -> cannotPlay?.let { state.withItemFailure(state.settled, it) } ?: state },
                onItemSettled = { position -> media?.let { viewModel.onItemSettled(it.pk, position) } },
                // The settled page only: its neighbours are composed too, and their tiles may be on the grid as well.
                sharedWithTile = page == pagerState.settledPage,
            )
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}

/**
 * Plays through the video cache (spec 8.3). Mock mode never touches the network, so it does not load the WebView for its
 * user agent; a real library uses the WebView's own, read once per process (`videoRequestUserAgent`, which a carousel's
 * video starts written ahead use too, R26). A refused link is not retried by the player (R85, `VideoLoadErrorPolicy`): it
 * reaches `onPlayerError` at once. It starts once 1 s is buffered (`viewerLoadControl`, R26).
 */
@OptIn(UnstableApi::class)
@Composable
private fun rememberViewerPlayer(container: AppContainer): ExoPlayer {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(videoMediaSourceFactory(context, container.videoCache.cache, container.videoRequestUserAgent))
            .setLoadControl(viewerLoadControl())
            .build()
            .apply { repeatMode = Player.REPEAT_MODE_ONE }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    return player
}

/** One page. [player] is non-null only for the page that is currently playing (for a carousel: while its settled item plays). */
@Composable
fun ViewerPage(
    media: MediaEntity?,
    player: Player?,
    collections: List<String>,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenInstagram: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Why the video cannot play, shown over the thumbnail above Open on Instagram (spec 8.5); null while it can. */
    unavailable: String? = null,
    /**
     * A carousel post's visit: its items, settled item and messages ([CarouselPager]). Null (a carousel that is not the settled
     * post) shows its cover as item 0, with "1/N". Ignored for any other post.
     */
    carousel: CarouselUiState? = null,
    /** A carousel post's pager settled on the item at this page. */
    onItemSettled: (Int) -> Unit = {},
    /** This page's media grows from and shrinks back into the post's grid tile (the shared element, `sharedMedia`). */
    sharedWithTile: Boolean = false,
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (media == null) return@Box
        val carouselState = if (media.type == MediaType.CAROUSEL) carousel ?: CarouselUiState() else null
        val carouselPager = carouselState?.let { rememberCarouselPagerState(it) }
        PostMedia(
            media, player, onTogglePlay, carouselState, carouselPager, onItemSettled,
            Modifier.fillMaxSize().then(if (sharedWithTile) Modifier.sharedMedia(media.pk) else Modifier),
        )
        val isVideo = when (media.type) {
            MediaType.REEL, MediaType.VIDEO -> true
            // On a carousel the settled item decides: its video items play under the same mute setting.
            MediaType.CAROUSEL -> carouselState?.settledItem?.type == MediaType.VIDEO
            MediaType.IMAGE -> false
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            if (carouselState != null && carouselPager != null) {
                CarouselDots(carouselPager, carouselState.items.size, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            }
            Overlay(media, collections, isVideo, muted, unavailable, onToggleMute, onOpenInstagram)
        }
    }
}

/**
 * The current post's media, filling [modifier]: the playing video, the thumbnail, or for a carousel its [CarouselPager] (over
 * [carouselPager], hoisted so the page can put the dots with the overlay).
 *
 * One box, tagged [POST_MEDIA_TAG], wraps whatever shows, and [modifier] (with the shared element, when this is the settled page)
 * goes on it: the thumbnail giving way to the video as it starts, or a carousel moving to another item, never replaces the shared
 * element's node mid-transition. For a carousel it is the whole pager: the post shrinks into its tile whatever item shows.
 */
@Composable
private fun PostMedia(
    media: MediaEntity,
    player: Player?,
    onTogglePlay: () -> Unit,
    carousel: CarouselUiState?,
    carouselPager: PagerState?,
    onItemSettled: (Int) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.testTag(POST_MEDIA_TAG)) {
        val fill = Modifier.fillMaxSize()
        when {
            carousel != null && carouselPager != null ->
                CarouselPager(media, carousel, player, onItemSettled, fill, pagerState = carouselPager, onTogglePlay = onTogglePlay)
            player != null -> VideoFrame(player, onTogglePlay, fill) { Thumbnail(media.thumbPath, Modifier.fillMaxSize(), ContentScale.Fit) }
            media.thumbPath != null -> Thumbnail(media.thumbPath, fill, ContentScale.Fit)
            else -> Box(fill) {
                Text(
                    "Not available on Instagram",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

/**
 * The viewer's one video surface, for a post and for a carousel's video item alike, tagged [VIDEO_SURFACE_TAG]; a tap pauses
 * or plays it. [shutter] shows until the first frame.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun VideoFrame(player: Player, onTogglePlay: () -> Unit, modifier: Modifier = Modifier, shutter: @Composable () -> Unit) {
    // A TextureView, not the default SurfaceView: on the emulator a SurfaceView created for a page the owner swiped
    // back to often never got its surface, so the audio played under the thumbnail (6 of 8 tries; 0 of 8 with this).
    ContentFrame(
        player = player,
        surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
        modifier = modifier.fillMaxSize().testTag(VIDEO_SURFACE_TAG).clickable(onClick = onTogglePlay),
        contentScale = ContentScale.Fit,
        shutter = shutter,
    )
}

@Composable
private fun Overlay(
    media: MediaEntity,
    collections: List<String>,
    isVideo: Boolean,
    muted: Boolean,
    unavailable: String?,
    onToggleMute: () -> Unit,
    onOpenInstagram: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(media.pk) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Text("@${media.author}", color = Color.White, style = MaterialTheme.typography.titleSmall)
        media.caption?.let { caption ->
            Text(
                caption,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp).clickable { expanded = !expanded },
            )
        }
        if (collections.isNotEmpty()) {
            Row(
                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                collections.forEach { name ->
                    Text(
                        name,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        unavailable?.let { message ->
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OverlayIconButton("Open on Instagram", R.drawable.ic_open_in_new) { onOpenInstagram(Permalinks.of(media.type, media.code)) }
            Spacer(Modifier.weight(1f))
            // The sound belongs to video: a reel, a video post, or a carousel while its settled item is a video (spec 2026-10-12 §3.4).
            if (isVideo) {
                if (muted) OverlayIconButton("Unmute", R.drawable.ic_volume_off, onToggleMute)
                else OverlayIconButton("Mute", R.drawable.ic_volume_up, onToggleMute)
            }
        }
    }
}

/**
 * An icon button over the picture: a white [icon] on a dark disc, so it reads on any frame. [label] is its content description
 * and its tooltip (a long press shows it), and no text is drawn.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class) // androidx.annotation.OptIn is the one imported here, for UnstableApi
@Composable
private fun OverlayIconButton(label: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)) {
            Icon(painterResource(icon), contentDescription = label, tint = Color.White)
        }
    }
}
