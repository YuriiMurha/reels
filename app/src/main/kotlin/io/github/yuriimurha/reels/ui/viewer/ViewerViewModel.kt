package io.github.yuriimurha.reels.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.yuriimurha.reels.data.db.CarouselItemEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.CarouselRepository
import io.github.yuriimurha.reels.data.media.ImageAhead
import io.github.yuriimurha.reels.data.media.ItemImage
import io.github.yuriimurha.reels.data.media.ItemsResult
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.media.isVideo
import io.github.yuriimurha.reels.data.media.videoLinkNeedsRefresh
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The settled post's carousel visit, for the post [pk]: what its [CarouselPager] shows. */
data class SettledCarousel(val pk: String, val state: CarouselUiState)

class ViewerViewModel(
    source: MediaSource,
    startIndex: Int,
    private val library: LibraryRepository,
    private val resolver: VideoSourceResolver,
    private val settings: SettingsStore,
    /** The container's one repository (R8): a carousel's items, images and video items, at most one `mediaInfo` a visit. */
    private val carousels: CarouselRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val items: Flow<PagingData<MediaEntity>> = library.pager(source, initialIndex = startIndex).cachedIn(viewModelScope)
    val muted: Flow<Boolean> = settings.muted

    private val _carousel = MutableStateFlow<SettledCarousel?>(null)

    /** The settled post's carousel: its items, settled item and messages. Null while the settled post is not a carousel. */
    val carousel: StateFlow<SettledCarousel?> = _carousel.asStateFlow()

    fun toggleMute() {
        viewModelScope.launch { settings.setMuted(!settings.muted.first()) }
    }

    /**
     * The settled unit's source: a post's video, or the video of item [position] of a carousel post. If the next-item prefetch
     * still has a request out for this very post, that request is waited for instead of sending a second one (R77). If that
     * prefetch is cancelled meanwhile (a newer prefetch replaces it, or a refresh makes way for the visible item), the settle
     * is not: it asks the resolver itself, which costs one more request. The settle's own cancellation still propagates.
     */
    suspend fun resolveVideo(media: MediaEntity, position: Int? = null): VideoSource? {
        if (position != null && media.type == MediaType.CAROUSEL) return resolveItem(media, position)
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

    /** The settled carousel post's visit, or null while the settled post is not a carousel. */
    private var carouselVisit: CarouselVisit? = null

    /**
     * A page settled on [media]. A coming back to an item is a new visit, with a new refresh. The prefetch still out is
     * cancelled, unless it is for [media] itself: the settle then waits for that request ([resolveVideo]) instead.
     * A carousel post shown again gets a new visit at item 0 (R8); the same post settling again in place (the list moved under
     * it) keeps the visit it has, and so its fill and its item, and takes the newer row (a sync may have stored its cover since).
     * A list move reaches here as exactly that, the same post at its new index: the screen reports no settle while its list
     * and its pager's layout disagree (C1), so the post that slides into the old index for an instant never settles.
     */
    fun onSettled(media: MediaEntity?) {
        media?.let { refreshed -= it.pk }
        prefetch?.takeIf { it.pk != media?.pk }?.answer?.cancel()
        val current = carouselVisit
        if (media?.pk == current?.media?.pk) {
            if (media != null && current != null) current.media = media
            return
        }
        current?.end()
        val next = media?.takeIf { it.type == MediaType.CAROUSEL }?.let { CarouselVisit(it) }
        carouselVisit = next
        _carousel.value = next?.let { SettledCarousel(it.media.pk, CarouselUiState(loading = true)) }
        next?.start()
    }

    /**
     * The pager of the carousel post [pk] settled on the item at [position]: that item's image is fetched, if it needs one, and
     * the visit's queue of pictures fetched ahead (R23) takes the items after it first from now on. Nothing for any other post,
     * and never the next post's prefetch (R11).
     */
    fun onItemSettled(pk: String, position: Int) {
        val current = carouselVisit?.takeIf { it.media.pk == pk } ?: return
        current.update { it.copy(settled = position) }
        current.loadImage(position)
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
     * Follows the settled units ([playSettledPages]): settle, resolve the visible unit, and only then prefetch the post after
     * it ([nextOf]). A new post (or the same post at another index) is a post settle ([onSettled]); a new item of the settled
     * carousel is not: it keeps the post's visit, its fill and the prefetch the post started. The prefetch is decided once a
     * post, on its first unit whose resolve returns (R11): an item swipe never sends one.
     */
    suspend fun followSettledPages(
        settled: Flow<SettledPage>,
        nextOf: (SettledPage) -> MediaEntity?,
        isStillSettled: (Playable) -> Boolean,
        onSettled: (SettledPage) -> Unit,
        onSource: (SettledPage, VideoSource) -> Unit,
    ) {
        var post: SettledPage? = null
        var prefetchDue = false
        playSettledPages(
            settled = settled,
            resolve = ::resolveVideo,
            isStillSettled = isStillSettled,
            onSettled = { page ->
                if (post?.index != page.index || post?.media?.pk != page.media?.pk) {
                    this.onSettled(page.media)
                    prefetchDue = true
                }
                post = page
                onSettled(page)
            },
            afterResolved = { page ->
                if (prefetchDue) {
                    prefetchDue = false
                    prefetch(nextOf(page))
                }
            },
            onSource = onSource,
        )
    }

    /**
     * The player failed on [media] (on its item [position], for a carousel). The first time that is a 403 or 410 (a link that
     * expired or was revoked) the link is renewed once, even if it looked fresh, and the answer is returned to play again. A
     * second failure, or any other kind, gives up: [VideoSource.Unavailable] says so over the thumbnail (or the item).
     */
    suspend fun recover(media: MediaEntity, error: Throwable, position: Int? = null): VideoSource {
        if (position != null && media.type == MediaType.CAROUSEL) return recoverItem(media, position, error)
        if (!isExpiredLinkError(error) || !refreshed.add(media.pk)) return VideoSource.Unavailable(CANT_PLAY)
        // The prefetch for the NEXT item may still be out in the interactive lane's FIFO: the visible item's refresh must not
        // queue behind it (R77, R79). A prefetch for this very item is left alone.
        prefetch?.takeIf { it.pk != media.pk }?.answer?.cancel()
        return resolver.resolve(media, forceRefresh = true) ?: VideoSource.Unavailable(CANT_PLAY)
    }

    /** A carousel's item at [index]: a video item through the repository, under `pk_position`; an image item, nothing. */
    private suspend fun resolveItem(media: MediaEntity, index: Int): VideoSource? {
        val current = carouselVisit?.takeIf { it.media.pk == media.pk } ?: return null
        // Until the fill is in, nobody knows what item 0 is: wait for it (in the post's scope, R8), then decide.
        val item = current.awaitItems()?.videoItemAt(index) ?: return null
        return current.awaitOrNull { carousels.video(media, item.position, current.visit) }
    }

    /**
     * As [recover], for a carousel's video item: one forced renewal per item per visit, and the repository's own visit rule. The
     * player not reaching the CDN at all is no link problem: the item says it is offline unless its bytes are all cached (R16).
     */
    private suspend fun recoverItem(media: MediaEntity, index: Int, error: Throwable): VideoSource {
        val current = carouselVisit?.takeIf { it.media.pk == media.pk } ?: return VideoSource.Unavailable(CANT_LOAD_ITEM)
        if (isNetworkError(error)) return carousels.unreachable(media, index)
        if (!isExpiredLinkError(error) || !current.refreshed.add(index)) return VideoSource.Unavailable(CANT_LOAD_ITEM)
        val item = current.awaitItems()?.videoItemAt(index) ?: return VideoSource.Unavailable(CANT_LOAD_ITEM)
        // As for a post (R79): the next post's prefetch must not hold this renewal up in the interactive lane.
        prefetch?.takeIf { it.pk != media.pk }?.answer?.cancel()
        return current.awaitOrNull { carousels.video(media, item.position, current.visit, forceRefresh = true) }
            ?: VideoSource.Unavailable(CANT_LOAD_ITEM)
    }

    private fun ItemsResult.videoItemAt(index: Int): CarouselItemEntity? =
        (this as? ItemsResult.Items)?.items?.getOrNull(index)?.takeIf { it.type == MediaType.VIDEO }

    /**
     * One visit of a carousel post (R8): ONE [CarouselRepository.Visit] (one `mediaInfo` at most, the fill and every renewal
     * together), and a scope that only leaving the post cancels. The fill, the images, the queue of pictures fetched ahead
     * (R23) and the items' video resolves all run there, so an item swipe (which cancels the settle that waits for them) never
     * cancels a call that may have spent the visit.
     */
    private inner class CarouselVisit(media: MediaEntity) {
        /** The post's row as last settled: a re-settle in place brings the newer one, which the visit's later calls use. */
        var media: MediaEntity = media
        val visit = CarouselRepository.Visit()
        private val job = SupervisorJob(viewModelScope.coroutineContext[Job])
        private val scope = CoroutineScope(viewModelScope.coroutineContext + job)

        /** The fill: the stored items, or the visit's one request for them. Started with the visit. */
        val items: Deferred<ItemsResult> = scope.async { carousels.items(media, visit) }

        /** Pages whose image was asked for in this visit: once each (a failure says so until the post is shown again). */
        private val imagesAsked = HashSet<Int>()

        /** R23: pages the queue of pictures fetched ahead has taken, done or out: each once a visit, whatever came of it. */
        private val fetchedAhead = HashSet<Int>()

        /** Pages whose video link was renewed after the player refused it, in this visit. */
        val refreshed = HashSet<Int>()

        fun start() {
            scope.launch {
                when (val result = items.await()) {
                    is ItemsResult.Items -> update { it.copy(items = result.items.map(::toUi), loading = false) }
                    is ItemsResult.CoverOnly -> update { it.copy(coverOnlyMessage = result.message, loading = false) }
                }
                current()?.let { loadImage(it.settled) }
                fetchAhead()
            }
        }

        /**
         * R23: the post's other pictures, downloaded while the owner looks at it, ONE at a time: the CDN lane allows two, so the
         * second stays free for the item on screen ([loadImage]). Each turn takes the next page by the settled item as it is
         * then, so an item settle reorders what is left without restarting it or cancelling the download that is out. Only
         * image items past the cover, and none shown or asked for on demand already; through [CarouselRepository.prefetchImage]
         * alone, whose only request to Instagram is the visit's one renewal for a refused link (R25), and a picture that does
         * not come never shows a failure (its item says why once it settles). It ends when nothing is left, when the
         * repository says to stop (the CDN's 429, offline, a post gone, a refusal the renewal cannot help: P1, R25), or with the
         * visit.
         */
        private suspend fun fetchAhead() {
            while (true) {
                val page = nextAhead() ?: return
                fetchedAhead += page
                val position = current()?.items?.getOrNull(page)?.position ?: return
                when (val ahead = carousels.prefetchImage(media, position, visit)) {
                    is ImageAhead.Shown -> update { state -> state.withItem(page) { it.copy(imagePath = ahead.path) } }
                    ImageAhead.Skipped -> Unit
                    ImageAhead.Stop -> return
                }
            }
        }

        /**
         * The page to fetch ahead next, or null: the items after the settled one in order, then those before it, the nearest
         * first. Never item 0 (the cover, R10), a video item (it streams and is cached as it plays), a picture already shown,
         * one the item's own [loadImage] asked for, or one taken before.
         */
        private fun nextAhead(): Int? {
            val state = current() ?: return null
            val settled = state.settled.coerceIn(0, state.items.size)
            val order = (settled + 1 until state.items.size) + (settled - 1 downTo 1)
            return order.firstOrNull { page ->
                val item = state.items[page]
                item.type == MediaType.IMAGE && item.imagePath == null && page !in imagesAsked && page !in fetchedAhead
            }
        }

        fun end() = job.cancel()

        /** This visit's state, while it is still the settled post's. */
        fun current(): CarouselUiState? = if (this === carouselVisit) _carousel.value?.state else null

        /** Changes this visit's state; nothing once the visit has ended (another post settled). */
        fun update(change: (CarouselUiState) -> CarouselUiState) {
            if (this === carouselVisit) _carousel.update { it?.copy(state = change(it.state)) }
        }

        /**
         * Item [index]'s image, on demand, once a visit. Item 0's is the cover the sync stored (taken from the first item, by the
         * same rule), so it is only fetched for a post without one. A video item has none to fetch, and a picture the queue
         * fetched ahead (R23) is shown already. If the queue's download of this very item is out, the repository waits for it.
         */
        fun loadImage(index: Int) {
            val item = current()?.items?.getOrNull(index) ?: return
            if (item.type != MediaType.IMAGE || item.imagePath != null) return
            if (index == 0 && media.thumbPath != null) return
            if (!imagesAsked.add(index)) return
            scope.launch {
                val shown: (CarouselItemUi) -> CarouselItemUi = when (val image = carousels.image(media, item.position, visit)) {
                    is ItemImage.File -> { entry -> entry.copy(imagePath = image.path) }
                    is ItemImage.Failed -> { entry -> entry.copy(failure = image.message) }
                }
                update { state -> state.withItem(index, shown) }
            }
        }

        /** The fill's answer, waited for; null when the visit ended first. A cancelled caller is still cancelled. */
        suspend fun awaitItems(): ItemsResult? = awaitCall(items)

        /**
         * Runs [block] in this visit's scope and waits for it. The caller's cancellation (a newer settle) stops the wait, not
         * the call; the call's own cancellation (the post was left) answers null.
         */
        suspend fun <T> awaitOrNull(block: suspend () -> T): T? = awaitCall(scope.async { block() })

        private suspend fun <T> awaitCall(call: Deferred<T>): T? = try {
            call.await()
        } catch (e: CancellationException) {
            // `await` throws this both when the call was cancelled and when this caller was; only the second ends the caller.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private companion object {
        /** No width or height: they may be 0 or mixed (Task 1), and the viewer fits every item instead of sizing from them. */
        fun toUi(item: CarouselItemEntity) = CarouselItemUi(item.position, item.type)

        /** The state with the item at page [index] changed by [change]; unchanged if there is none. */
        fun CarouselUiState.withItem(index: Int, change: (CarouselItemUi) -> CarouselItemUi): CarouselUiState =
            copy(items = items.mapIndexed { i, entry -> if (i == index) change(entry) else entry })
    }
}
