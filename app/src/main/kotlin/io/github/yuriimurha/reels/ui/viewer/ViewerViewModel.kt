package io.github.yuriimurha.reels.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.media.isVideo
import io.github.yuriimurha.reels.data.media.videoLinkNeedsRefresh
import io.github.yuriimurha.reels.data.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ViewerViewModel(
    source: MediaSource,
    startIndex: Int,
    private val library: LibraryRepository,
    private val resolver: VideoSourceResolver,
    private val settings: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val items: Flow<PagingData<MediaEntity>> = library.pager(source, initialIndex = startIndex).cachedIn(viewModelScope)
    val muted: Flow<Boolean> = settings.muted

    fun toggleMute() {
        viewModelScope.launch { settings.setMuted(!settings.muted.first()) }
    }

    suspend fun resolveVideo(media: MediaEntity): VideoSource? = resolver.resolve(media)

    suspend fun collectionNames(pk: String): List<String> = library.collectionsOf(pk).map { it.name }

    // Everything below runs on the main thread (the screen's effects and viewModelScope), so no locking.

    private var prefetchJob: Job? = null

    /** Items whose link was prefetched (or whose prefetch was started): one prefetch per item, ever (spec 8.2). */
    private val prefetched = HashSet<String>()

    /** Items that already had their one link refresh after the player was refused, in their current visit. */
    private val refreshed = HashSet<String>()

    /**
     * A page settled on [media]; [next] is the item after it. If [next] is a video whose link has run out, or is about to,
     * its link is renewed now, so that swiping on finds it ready. One job at a time: a newer settle cancels the one still
     * out. Through the same resolver, so on the interactive lane, never a second request path.
     */
    fun onSettled(media: MediaEntity?, next: MediaEntity?) {
        media?.let { refreshed -= it.pk } // coming back to an item is a new visit with a new refresh
        prefetchJob?.cancel()
        prefetchJob = null
        if (next == null || !next.isVideo || !next.videoLinkNeedsRefresh(now()) || !prefetched.add(next.pk)) return
        prefetchJob = viewModelScope.launch {
            try {
                resolver.resolve(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A prefetch is a head start, nothing more: the real resolve on that page reports whatever is wrong.
            }
        }
    }

    /**
     * The player failed on [media]. The first time that is a 403 or 410 (a link that expired or was revoked) the link is
     * renewed once, even if it looked fresh, and the answer is returned to play again. A second failure, or any other
     * kind, gives up: [VideoSource.Unavailable] says so over the thumbnail.
     */
    suspend fun recover(media: MediaEntity, error: Throwable): VideoSource {
        if (!isExpiredLinkError(error) || !refreshed.add(media.pk)) return VideoSource.Unavailable(CANT_PLAY)
        return resolver.resolve(media, forceRefresh = true) ?: VideoSource.Unavailable(CANT_PLAY)
    }
}
