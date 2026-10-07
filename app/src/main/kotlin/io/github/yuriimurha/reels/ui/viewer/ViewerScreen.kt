package io.github.yuriimurha.reels.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.data.media.videoMediaSourceFactory
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Permalinks
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.common.Thumbnail
import io.github.yuriimurha.reels.ui.common.carouselLabel
import kotlinx.coroutines.flow.distinctUntilChanged

/** The player stopped on [media] with [error]; a new instance per failure, so the effect that handles it restarts. */
private class PlayerFailure(val media: MediaEntity, val error: Throwable)

/** Full-screen vertical pager over the same list the grid showed (spec 9.3). One player is reused across pages. */
@Composable
fun ViewerScreen(source: MediaSource, startIndex: Int, onIndexSettled: (Int) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel = viewModel(key = "viewer:${source.encode()}:$startIndex") {
        ViewerViewModel(source, startIndex, container.library, container.videoResolver, container.settings)
    }
    val items = viewModel.items.collectAsLazyPagingItems()
    val muted by viewModel.muted.collectAsStateWithLifecycle(initialValue = false)
    val pagerState = rememberPagerState(initialPage = startIndex) { items.itemCount }
    val player = rememberViewerPlayer(container)
    /** The item the player has loaded, or null. */
    var playing by remember { mutableStateOf<MediaEntity?>(null) }
    /** The pk of the item that cannot play, with the reason shown under its thumbnail. */
    var unavailable by remember { mutableStateOf<Pair<String, String>?>(null) }
    var failure by remember { mutableStateOf<PlayerFailure?>(null) }

    val settledPk = { items.itemSnapshotList.getOrNull(pagerState.settledPage)?.pk }
    val isStillSettled = { media: MediaEntity -> settledPk() == media.pk }
    val present = { media: MediaEntity, source: VideoSource ->
        when (source) {
            is VideoSource.Play -> {
                player.setMediaItem(source.toMediaItem())
                player.prepare()
                player.play()
                unavailable = null
                playing = media
            }
            is VideoSource.Unavailable -> {
                player.stop()
                player.clearMediaItems()
                playing = null
                unavailable = media.pk to source.message
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
    LaunchedEffect(pagerState, items) {
        viewModel.followSettledPages(
            settled = snapshotFlow { SettledPage(pagerState.settledPage, items.itemSnapshotList.getOrNull(pagerState.settledPage)) }
                .distinctUntilChanged { a, b -> a.index == b.index && a.media?.pk == b.media?.pk },
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
    // A refused link is renewed once; the effect restarts for every new failure and is cancelled by the next settle (failure = null).
    LaunchedEffect(failure) {
        val failed = failure ?: return@LaunchedEffect
        playIfStillSettled(
            failed.media,
            resolve = { viewModel.recover(it, failed.error) },
            isStillSettled = isStillSettled,
            onSource = { present(failed.media, it) },
        )
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
            ViewerPage(
                media = media,
                player = player.takeIf { media != null && media.pk == playing?.pk },
                collections = collections,
                muted = muted,
                onToggleMute = viewModel::toggleMute,
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                onOpenInstagram = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                unavailable = unavailable?.takeIf { media != null && it.first == media.pk }?.second,
            )
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}

/**
 * Plays through the video cache (spec 8.3). Mock mode never touches the network, so it does not load the WebView for its
 * user agent; a real library uses the WebView's own, read once per process. A refused link is not retried by the player
 * (R85, `VideoLoadErrorPolicy`): it reaches `onPlayerError` at once.
 */
@OptIn(UnstableApi::class)
@Composable
private fun rememberViewerPlayer(container: AppContainer): ExoPlayer {
    val context = LocalContext.current
    val player = remember {
        val userAgent = if (container.usesFake) null else container.videoUserAgent
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(videoMediaSourceFactory(context, container.videoCache.cache, userAgent))
            .build()
            .apply { repeatMode = Player.REPEAT_MODE_ONE }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    return player
}

/** One page. [player] is non-null only for the page that is currently playing. */
@OptIn(UnstableApi::class)
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
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (media == null) return@Box
        val isVideo = media.type == MediaType.REEL || media.type == MediaType.VIDEO
        when {
            // A TextureView, not the default SurfaceView: on the emulator a SurfaceView created for a page the owner swiped
            // back to often never got its surface, so the audio played under the thumbnail (6 of 8 tries; 0 of 8 with this).
            player != null -> ContentFrame(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize().clickable(onClick = onTogglePlay),
                contentScale = ContentScale.Fit,
                shutter = { Thumbnail(media.thumbPath, Modifier.fillMaxSize(), ContentScale.Fit) },
            )
            media.thumbPath != null -> Thumbnail(media.thumbPath, Modifier.fillMaxSize(), ContentScale.Fit)
            else -> Text(
                "Not available on Instagram",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (media.type == MediaType.CAROUSEL) {
            // Only the cover is shown (spec 9.3), so say how many slides the real post has.
            Text(
                carouselLabel(media),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Overlay(media, collections, isVideo, muted, unavailable, onToggleMute, onOpenInstagram, Modifier.align(Alignment.BottomStart))
    }
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
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onOpenInstagram(Permalinks.of(media.type, media.code)) }) { Text("Open on Instagram") }
            if (isVideo) TextButton(onClick = onToggleMute) { Text(if (muted) "Unmute" else "Mute") }
        }
    }
}
