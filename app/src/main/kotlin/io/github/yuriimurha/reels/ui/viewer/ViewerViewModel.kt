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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /**
     * The settled item's source. If the next-item prefetch still has a request out for this very item, that request is
     * waited for instead of sending a second one (R77). If that prefetch is cancelled meanwhile (a newer prefetch replaces
     * it, or a refresh makes way for the visible item), the settle is not: it asks the resolver itself, which costs one more
     * request. The settle's own cancellation still propagates.
     */
    suspend fun resolveVideo(media: MediaEntity): VideoSource? {
        // Only while it is still out: a finished prefetch's answer ages, the resolver (which reads the renewed row) does not.
        val pending = prefetch?.takeIf { it.pk == media.pk && it.answer.isActive }?.answer
        val answer = try {
            pending?.await()
        } catch (e: CancellationException) {
            // `await` throws this both when the prefetch was cancelled and when this caller was; only the second ends the settle.
            currentCoroutineContext().ensureActive()
            null
        }
        return answer ?: resolver.resolve(media)
    }

    suspend fun collectionNames(pk: String): List<String> = library.collectionsOf(pk).map { it.name }

    // Everything below runs on the main thread (the screen's effects and viewModelScope), so no locking.

    /** The prefetch that is out (or finished): which item, and its answer for a settle that arrives while it is out. */
    private class Prefetch(val pk: String, val answer: Deferred<VideoSource?>)

    private var prefetch: Prefetch? = null

    /** Items whose link was prefetched (or whose prefetch was started): one prefetch per item, ever (spec 8.2). */
    private val prefetched = HashSet<String>()

    /** Items that already had their one link refresh after the player was refused, in their current visit. */
    private val refreshed = HashSet<String>()

    /**
     * A page settled on [media]. A coming back to an item is a new visit, with a new refresh. The prefetch still out is
     * cancelled, unless it is for [media] itself: the settle then waits for that request ([resolveVideo]) instead.
     */
    fun onSettled(media: MediaEntity?) {
        media?.let { refreshed -= it.pk }
        prefetch?.takeIf { it.pk != media?.pk }?.answer?.cancel()
    }

    /**
     * If [next], the item after the settled one, is a video whose link has run out or is about to, renews it now, so that
     * swiping on finds it ready. Called once the settled item's own resolve has returned (R77), so the visible item's
     * request is never queued behind this one. Through the same resolver, so on the interactive lane and under the same
     * session check: never a second request path. One at a time, and at most once per item.
     */
    fun prefetch(next: MediaEntity?) {
        if (next == null || !next.isVideo || !next.videoLinkNeedsRefresh(now()) || !prefetched.add(next.pk)) return
        prefetch?.answer?.cancel()
        prefetch = Prefetch(
            next.pk,
            viewModelScope.async {
                try {
                    resolver.resolve(next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A prefetch is a head start, nothing more: the real resolve on that page reports whatever is wrong.
                    null
                }
            },
        )
    }

    /**
     * Follows the settled pages ([playSettledPages]): settle, resolve the visible item, and only then prefetch the one
     * after it ([nextOf]).
     */
    suspend fun followSettledPages(
        settled: Flow<SettledPage>,
        nextOf: (SettledPage) -> MediaEntity?,
        isStillSettled: (MediaEntity) -> Boolean,
        onSettled: (SettledPage) -> Unit,
        onSource: (MediaEntity, VideoSource) -> Unit,
    ) = playSettledPages(
        settled = settled,
        resolve = ::resolveVideo,
        isStillSettled = isStillSettled,
        onSettled = { page ->
            this.onSettled(page.media)
            onSettled(page)
        },
        afterResolved = { page -> prefetch(nextOf(page)) },
        onSource = onSource,
    )

    /**
     * The player failed on [media]. The first time that is a 403 or 410 (a link that expired or was revoked) the link is
     * renewed once, even if it looked fresh, and the answer is returned to play again. A second failure, or any other
     * kind, gives up: [VideoSource.Unavailable] says so over the thumbnail.
     */
    suspend fun recover(media: MediaEntity, error: Throwable): VideoSource {
        if (!isExpiredLinkError(error) || !refreshed.add(media.pk)) return VideoSource.Unavailable(CANT_PLAY)
        // The prefetch for the NEXT item may still be out in the interactive lane's FIFO: the visible item's refresh must not
        // queue behind it (R77, R79). A prefetch for this very item is left alone.
        prefetch?.takeIf { it.pk != media.pk }?.answer?.cancel()
        return resolver.resolve(media, forceRefresh = true) ?: VideoSource.Unavailable(CANT_PLAY)
    }
}
