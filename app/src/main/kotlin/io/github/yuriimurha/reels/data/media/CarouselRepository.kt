package io.github.yuriimurha.reels.data.media

import android.net.Uri
import androidx.core.net.toUri
import io.github.yuriimurha.reels.data.db.CarouselItemDao
import io.github.yuriimurha.reels.data.db.CarouselItemEntity
import io.github.yuriimurha.reels.data.db.MediaDao
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.itemShapeChanged
import io.github.yuriimurha.reels.data.db.toEntity
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteCarouselItem
import io.github.yuriimurha.reels.sync.pacing.Pacer
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** A carousel post's items, as the viewer gets them from [CarouselRepository.items]. */
sealed interface ItemsResult {
    /** The post's items in position order; never empty. */
    data class Items(val items: List<CarouselItemEntity>) : ItemsResult

    /** Only the cover shows (the indicator reads "1/N"): the items are not known. [message] says why; null when there is nothing to say. */
    data class CoverOnly(val message: String?) : ItemsResult
}

/** One item's image, from [CarouselRepository.image]. */
sealed interface ItemImage {
    /** The downloaded image, on disk at [path]. */
    data class File(val path: String) : ItemImage

    /** No image: show [message] over the cover, next to Open on Instagram. */
    data class Failed(val message: String) : ItemImage
}

/**
 * What [CarouselRepository.prefetchImage] or [CarouselRepository.prefetchPoster] did for one item (R23, R25, R26): what the
 * queue of pictures fetched ahead does next.
 */
sealed interface ImageAhead {
    /** The item's picture (an image item's image, a video item's poster) is on disk at [path]: it was already, or it was just downloaded. */
    data class Shown(val path: String) : ImageAhead

    /** Nothing to fetch for this item (the cover, an item of the other type, past the last item, no stored items): go on with the next. */
    data object Skipped : ImageAhead

    /**
     * Fetch nothing more ahead in this visit: the CDN said 429, the network is down, the post is gone, or a link was refused
     * and the visit's one renewal could not help (spent on links that are refused too, or failed) (R25).
     */
    data object Stop : ImageAhead
}

/**
 * R26: how much of a carousel video item's start [CarouselRepository.preloadVideo] writes ahead: 1 MiB, a few seconds of a
 * typical reel, more than the player's 1 s start buffer needs.
 */
const val VIDEO_START_BYTES = 1L shl 20

/** What [CarouselRepository.preloadVideo] did for one video item (R26): what the queue does next. */
sealed interface StartAhead {
    /** The item's start is in the video cache: written now, or there already (nothing was fetched then). */
    data object Cached : StartAhead

    /** Nothing written for this item (not a video item, a stale or refused link, Mock mode): go on with the next. */
    data object Skipped : StartAhead

    /** Fetch nothing more ahead in this visit: the CDN said 429, the network is down, the post is gone (P1). */
    data object Stop : StartAhead
}

/**
 * A carousel's items on demand (spec 2026-10-12 §3.3). Stored items are used as they are. A post with none (synced before
 * items were kept, or a reply that did not carry them, R2/R4) gets them from one `mediaInfo` ([PacedMediaInfo]: the
 * interactive lane, under the session gates of R76/R79). A link that fails (an image the CDN refuses, an item with no image,
 * a video link that is stale, missing or refused by the player) renews the whole post's links with the same request.
 *
 * Pacing: a [Visit] allows ONE such request, the fill and the renewal together, whatever came of it (a failure, a cancelled
 * call): every other call in the visit uses what that one brought. Nothing here runs during a sync. Images download through
 * the cookieless CDN [MediaFetcher] on the Pacer's CDN lane, like the sync's thumbnails, into [images], so a viewed item shows
 * offline (R1). [prefetchImage] downloads an item's picture the same way before the owner swipes to it (R23: the viewer fetches
 * a carousel's other pictures ahead, one at a time); a refused link may spend the visit's one request there too (R25), never
 * a second one, and [prefetchPoster] does the same for a video item's poster (R26). A link the CDN refused is not requested
 * again in the visit. A video item plays from its link with its bytes cached under `{pk}_{position}` (spec §3.2), and
 * [preloadVideo] writes the first [VIDEO_START_BYTES] of it there ahead of the swipe (R26), from a link that plays as it is only:
 * it never renews.
 *
 * Every call answers; none throws except [CancellationException]. A failure never deletes the post, its cover or its items.
 * The debug log ([log]) gets one line per request and per failed image, never a link or a pk.
 */
class CarouselRepository(
    private val dao: CarouselItemDao,
    private val mediaDao: MediaDao,
    private val info: PacedMediaInfo,
    private val fetcher: MediaFetcher,
    private val images: CarouselImageStore,
    /** Its CDN lane carries the image downloads; its API lanes are [info]'s. */
    private val pacer: Pacer,
    /** True when every byte of the video under this cache key is on disk (`VideoCache.isFullyCached`). Called off the main thread. */
    private val isVideoCached: (cacheKey: String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    /** R3, Mock mode only: the bundled clip every video item plays; null for the real library. */
    private val sampleClip: Uri? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: ((String) -> Unit)? = null,
    /**
     * R20: told the post whose renewal stored items of another shape ([itemShapeChanged]), once they are stored, so its
     * downloaded item images and cached item videos (keyed by position) go. The container's is the sync's own.
     */
    private val itemEviction: MediaEviction = MediaEviction { },
    /** R26: writes a video item's start into the video cache, through the player's own cookieless stack. Its writes run on [pacer]'s CDN lane. */
    private val starts: VideoStarts = VideoStarts.NONE,
) {
    /**
     * One visit of a post, from the moment the viewer shows it until it leaves it: the visit's one `mediaInfo`, and what it
     * brought, which every later call of the visit shares. The viewer makes a new one each time the post is shown again.
     */
    class Visit {
        internal val lock = Mutex()
        internal var renewal: Renewal? = null

        /**
         * R23: one download of an item at a time in this visit. [image] and [prefetchImage] both hold the item's lock, so an
         * item asked for while its prefetch is out waits for that download and then finds its file. Apart from the visit's
         * request: a prefetch asks for that only by [renewal]'s rule, as [image] does (R25).
         */
        private val downloads = ConcurrentHashMap<Int, Mutex>()

        internal fun downloadOf(position: Int): Mutex = downloads.computeIfAbsent(position) { Mutex() }

        /**
         * R25, R26: the links the CDN refused in this visit: pictures (403, 404, 410: `MediaFetcher` does not tell them apart),
         * and video starts refused as running out (403, 410, R27). None is requested again in it, by the queue or by the item's
         * own load: a refused picture goes to the visit's renewal, a video item whose start was refused plays only a renewed link.
         */
        internal val refused: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * R27: video links a start ahead was refused with a 4xx that is not a link running out (a 404, a 408, ...). No start
         * asks them again in the visit, but the item's own play is the player's, as before R26 (R19: a video's 404 renews
         * nothing). A 403 or 410 goes into [refused] instead.
         */
        internal val startsRefused: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /**
         * N-M1: the start being written ahead now, by item position, until it has stopped and been checked (P2, D3, M2): the
         * item's own play waits for one of another link ([video]).
         */
        internal val startsOut = ConcurrentHashMap<Int, StartOut>()
    }

    /** N-M1: a start written ahead from [url]; [checked] completes once the write has stopped and its bytes were checked. */
    internal class StartOut(val url: String) {
        val checked: CompletableJob = Job()
    }

    /** Stored items; when none, one paced `mediaInfo` fills them (once a [visit]). Not a carousel: [ItemsResult.CoverOnly] (null). */
    suspend fun items(media: MediaEntity, visit: Visit): ItemsResult = try {
        loadItems(media, visit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ItemsResult.CoverOnly(CANT_LOAD)
    }

    /**
     * The image file of item [position], downloaded on demand; a refused or missing link renews the post's links once a [visit].
     * While the item's [prefetchImage] is out it waits for it, and then serves the file it stored; if it stored none, the item
     * is loaded as if there had been no prefetch, except that a link refused in this visit is not asked for again (R25): the
     * item goes straight to the visit's renewal.
     */
    suspend fun image(media: MediaEntity, position: Int, visit: Visit): ItemImage = try {
        loadImage(media, position, visit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ItemImage.Failed(CANT_LOAD)
    }

    /**
     * R23: item [position]'s picture, downloaded before the owner swipes to it (the viewer calls this for a carousel's other
     * pictures, one at a time). Only an IMAGE item past the cover (item 0 shows the stored cover, R10) with stored items, of a
     * post still in the library, through the CDN lane with the same fetcher and store as [image]; it shares the visit's
     * per-item download lock with [image], so the two never download one item twice.
     *
     * Pacing (R25): it never fills. A refused link (or none) may spend the [visit]'s one renewal by the same rule as [image]
     * (the first call that needs it sends it, every later one shares what it brought), and the item then loads from its
     * renewed link. A renewal spent already on the links stored now, or failed, stops the queue ([ImageAhead.Stop]), and so do
     * the CDN's 429, offline and a post gone (P1). A failure never shows: the item's own load says why once the owner gets
     * there. Throws only [CancellationException].
     */
    suspend fun prefetchImage(media: MediaEntity, position: Int, visit: Visit): ImageAhead = ahead(media, position, visit, MediaType.IMAGE)

    /**
     * R26: the poster of the VIDEO item at [position] (its `imageUrl`, the preview frame), downloaded ahead exactly as
     * [prefetchImage] downloads an image item's picture, into the same store under the same `{pk}_{position}` (a video item
     * has no other picture there), by the same rules: never the fill, the visit's one renewal for a refused link (R25), the
     * stale-link check (P2), the removed-post guard (D3), and the CDN's 429 or offline stop the queue (P1). The viewer shows
     * it until the video's first frame. Only an item stored as a video: an image there (another shape, R20) is skipped.
     */
    suspend fun prefetchPoster(media: MediaEntity, position: Int, visit: Visit): ImageAhead = ahead(media, position, visit, MediaType.VIDEO)

    /**
     * R26: the first [VIDEO_START_BYTES] of the VIDEO item at [position], written into the video cache under `{pk}_{position}` before
     * the owner swipes to it, one write at a time on the CDN lane, through the player's own cookieless stack ([starts]).
     *
     * Pacing: it never asks Instagram anything. Only a link [video] would play as it is (more than 10 minutes left) is used: a
     * stale one, or one with no link, is skipped, never renewed. A start already cached costs nothing, and neither does Mock
     * mode, whose clip is local. A link the CDN refuses is skipped quietly and never requested again in the visit by a
     * start; refused as running out (403, 410), the item's own play does not ask it again either ([video] renews it instead,
     * as it would after the player's own 403 or 410), while any other 4xx leaves the play to the player, as before (R27,
     * R19). The CDN's 429, offline, a failing cache (I1) and a post gone stop the queue (P1). Bytes written for a link that
     * was replaced meanwhile, or for a post that left the library, are dropped again (P2, D3), also when the write is
     * cancelled (M2), and while that check is out the item's play waits for it if the item has another link by then (N-M1).
     * Throws only [CancellationException]; cancelled, it returns once the write has stopped and been checked.
     */
    suspend fun preloadVideo(media: MediaEntity, position: Int, visit: Visit): StartAhead = try {
        loadStart(media, position, visit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unforeseen (a failing cache): every other item would meet it too.
        StartAhead.Stop
    }

    private suspend fun ahead(media: MediaEntity, position: Int, visit: Visit, type: MediaType): ImageAhead = try {
        loadAhead(media, position, visit, type)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unforeseen (an unsafe key, a failing store): every other item would meet it too.
        ImageAhead.Stop
    }

    /**
     * A playable source for the VIDEO item at [position], keyed `{pk}_{position}`: its stored link while fresh (by the reel
     * links' rule) or while its bytes are all cached (R78), else the link one renewal a [visit] brings. [forceRefresh] is the
     * player's refused link (a 403 or 410): only a different link is worth playing then. A link the CDN refused in this
     * visit, when its start was written ahead (R26), is not asked again either: the item plays its cached bytes if they are
     * all there, else a renewed link only.
     *
     * N-M1: while a start of this item written ahead from ANOTHER link (the item's link was replaced meanwhile: a reshape, a
     * sync, the renewal) has not stopped and been checked, the source is not handed out. That write may yet commit the other
     * link's bytes under the item's key, and a player reading the key past the write's lock would meet them at its next look
     * at the cache, in the middle of its own video; the check (M2) drops them first. A start of the very link played is the
     * same video, so the player may read past it at once.
     */
    suspend fun video(media: MediaEntity, position: Int, visit: Visit, forceRefresh: Boolean = false): VideoSource = try {
        loadVideo(media, position, visit, forceRefresh).also { visit.startOtherThan(position, it)?.checked?.join() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        VideoSource.Unavailable(CANT_LOAD)
    }

    /**
     * R16: the player could not reach the CDN at all for the VIDEO item at [position] (no connection). Unless every byte of it
     * is cached under `{pk}_{position}`, the item was never watched through and cannot play offline: it says
     * "Offline: this item isn't cached yet". With all its bytes cached the connection was not what failed: "Can't load this
     * item". Sends nothing.
     */
    suspend fun unreachable(media: MediaEntity, position: Int): VideoSource.Unavailable = try {
        val cached = withContext(ioDispatcher) { isVideoCached("${media.pk}_$position") }
        VideoSource.Unavailable(if (cached) CANT_LOAD else OFFLINE)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        VideoSource.Unavailable(CANT_LOAD)
    }

    private suspend fun loadItems(media: MediaEntity, visit: Visit): ItemsResult {
        if (media.type != MediaType.CAROUSEL) return ItemsResult.CoverOnly(null)
        val stored = dao.items(media.pk)
        if (stored.isNotEmpty()) return ItemsResult.Items(stored)
        return when (val renewal = renewal(media, visit, FILL)) {
            is Renewal.Items -> ItemsResult.Items(renewal.items)
            is Renewal.Failed -> ItemsResult.CoverOnly(renewal.message)
        }
    }

    private suspend fun loadImage(media: MediaEntity, position: Int, visit: Visit): ItemImage {
        // Not a carousel: no items to fill, so no request (a reel's link is the video resolver's).
        if (media.type != MediaType.CAROUSEL) return ItemImage.Failed(CANT_LOAD)
        // R23: the item's prefetch may be out. Its lock makes this wait for it and then find its file, not download it again.
        return visit.downloadOf(position).withLock { loadImageNow(media, position, visit) }
    }

    /** The item's own load, holding its download lock. [again]: its link was replaced while it downloaded, so it reads once more. */
    private suspend fun loadImageNow(media: MediaEntity, position: Int, visit: Visit, again: Boolean = true): ItemImage {
        val pk = media.pk
        withContext(ioDispatcher) { images.path(pk, position) }?.let { return ItemImage.File(it) }
        val item = storedItem(pk, position) ?: when (val renewal = renewal(media, visit, FILL)) {
            is Renewal.Failed -> return ItemImage.Failed(renewal.message)
            is Renewal.Items -> renewal.items.at(position) ?: return ItemImage.Failed(CANT_LOAD)
        }
        // M4: a video item Instagram sent no preview frame for has no poster to fetch, and none a renewal would bring: nothing
        // is asked (a poster never spends the visit's request on a link that is not there).
        if (item.type == MediaType.VIDEO && item.imageUrl.isEmpty()) return ItemImage.Failed(CANT_LOAD)
        val first = download(pk, position, item.imageUrl, visit)
        // P2: the post's items were replaced while it downloaded (a renewal, a sync): the item as stored now loads instead.
        if (first == Download.Replaced && again) return loadImageNow(media, position, visit, again = false)
        if (first != Download.Refused) return first.toImage()
        // The CDN refused the link (it expired), now or earlier in the visit (R25), or there is none: the post's links are
        // renewed, once a visit.
        val renewed = when (val renewal = renewal(media, visit, RENEW)) {
            is Renewal.Failed -> return ItemImage.Failed(renewal.message)
            is Renewal.Items -> renewal.items.at(position)
        }
        // Only a new link is worth a download: the one just refused would be refused again.
        if (renewed == null || renewed.imageUrl == item.imageUrl) return ItemImage.Failed(CANT_LOAD)
        return download(pk, position, renewed.imageUrl, visit).toImage()
    }

    /**
     * [prefetchImage] and [prefetchPoster]: the picture of the item at [position] if it is stored as a [type] item. The stored
     * items only, never the fill, and at most the visit's one renewal (R25).
     */
    private suspend fun loadAhead(media: MediaEntity, position: Int, visit: Visit, type: MediaType): ImageAhead {
        // Not a carousel: no items. Item 0: the stored cover shows it (R10), for an image and a video item alike. Neither is
        // downloaded ahead.
        if (media.type != MediaType.CAROUSEL || position <= 0) return ImageAhead.Skipped
        return visit.downloadOf(position).withLock { loadAheadNow(media, position, visit, type) }
    }

    /** Holding the item's download lock. [again]: its link was replaced while it downloaded, so it reads once more. */
    private suspend fun loadAheadNow(media: MediaEntity, position: Int, visit: Visit, type: MediaType, again: Boolean = true): ImageAhead {
        val pk = media.pk
        withContext(ioDispatcher) { images.path(pk, position) }?.let { return ImageAhead.Shown(it) }
        // The item as stored now, read under the lock (P2). None stored, none at this position (past the last item), or one
        // of the other type: nothing is asked.
        val item = storedItem(pk, position)?.takeIf { it.type == type } ?: return ImageAhead.Skipped
        // M4: a video item with no preview frame has no poster: passed, with no renewal and no stop (its start still comes).
        if (type == MediaType.VIDEO && item.imageUrl.isEmpty()) return ImageAhead.Skipped
        // As for download's own check after the bytes: a post deleted or unsaved gets no download at all.
        if (!isLive(pk)) return ImageAhead.Stop
        val what = if (type == MediaType.VIDEO) POSTER_LOG else PREFETCH_LOG
        return when (val first = download(pk, position, item.imageUrl, visit, what)) {
            Download.Refused -> renewAhead(media, position, item, visit, what)
            Download.Replaced -> if (again) loadAheadNow(media, position, visit, type, again = false) else ImageAhead.Skipped
            else -> first.toAhead()
        }
    }

    /** [preloadVideo]: the stored items only, never the fill and never a renewal. */
    private suspend fun loadStart(media: MediaEntity, position: Int, visit: Visit): StartAhead {
        if (media.type != MediaType.CAROUSEL) return StartAhead.Skipped
        // R3: Mock mode plays the bundled clip, which is in the app already: nothing to fetch ahead.
        if (sampleClip != null) return StartAhead.Skipped
        val pk = media.pk
        val item = storedItem(pk, position)?.takeIf { it.type == MediaType.VIDEO } ?: return StartAhead.Skipped
        // Only a link the item would play as it is: a stale one (or none) is never renewed for a start ahead, and one the CDN
        // refused in this visit is not asked again.
        val url = item.videoUrl?.takeIf { !item.videoLinkNeedsRefresh(now()) && it !in visit.refused && it !in visit.startsRefused }
            ?: return StartAhead.Skipped
        val key = "${pk}_$position"
        if (withContext(ioDispatcher) { starts.isCached(key, VIDEO_START_BYTES) }) return StartAhead.Cached
        // D3: a post deleted or unsaved gets no request.
        if (!isLive(pk)) return StartAhead.Stop
        // N-M1: out until it has stopped and been checked, finished or cancelled; the item's play waits for it meanwhile if
        // the item has another link by then.
        val out = StartOut(url)
        visit.startsOut[position] = out
        try {
            return writeStart(pk, position, url, key, visit)
        } finally {
            visit.startsOut.remove(position, out)
            out.checked.complete()
        }
    }

    /** [loadStart]'s write and what comes of it, with its link [url] under [key]. */
    private suspend fun writeStart(pk: String, position: Int, url: String, key: String, visit: Visit): StartAhead {
        val written = try {
            pacer.cdn { starts.write(key, url.toUri(), VIDEO_START_BYTES) }
        } catch (e: CancellationException) {
            // M2: a cancelled write keeps what it wrote. Checked as a finished one is, before the cancellation goes on.
            withContext(NonCancellable) { dropIfReplaced(pk, position, url, key) }
            throw e
        }
        // P2, D3: the post's items were replaced while this was written (a renewal or a sync, whose R20 drop may already have
        // run), or the post left the library: bytes written (all, or some before the connection dropped) may be another
        // slide's, so they go again. A cancel landing now does not skip it (N-M1: the item's play would read them).
        val dropped = (written == StartWrite.WRITTEN || written == StartWrite.OFFLINE) &&
            withContext(NonCancellable) { dropIfReplaced(pk, position, url, key) }
        return when (written) {
            StartWrite.WRITTEN, StartWrite.CACHED -> when {
                !dropped -> StartAhead.Cached
                isLive(pk) -> StartAhead.Skipped
                else -> StartAhead.Stop
            }
            StartWrite.EXPIRED -> {
                // R27: the answer the player renews a link on; the item's own play goes to the visit's renewal instead.
                visit.refused += url
                log?.invoke("$START_LOG failed (expired)")
                StartAhead.Skipped
            }
            StartWrite.REFUSED -> {
                visit.startsRefused += url
                log?.invoke("$START_LOG failed (refused)")
                StartAhead.Skipped
            }
            StartWrite.LIMITED -> StartAhead.Stop.also { log?.invoke("$START_LOG failed (cdn limit)") }
            StartWrite.OFFLINE -> StartAhead.Stop.also { log?.invoke("$START_LOG failed (offline)") }
            StartWrite.FAILED -> StartAhead.Stop.also { log?.invoke("$START_LOG failed (cache)") }
        }
    }

    /** P2, D3, M2: drops what is cached under [key] when the item no longer stores [url] or the post left. True when it did. */
    private suspend fun dropIfReplaced(pk: String, position: Int, url: String, key: String): Boolean {
        if (startStillStored(pk, position, url)) return false
        withContext(ioDispatcher) { starts.remove(key) }
        log?.invoke("$START_LOG dropped (link replaced)")
        return true
    }

    /** True while the post [pk] is live and its item at [position] is still the video of [url]. */
    private suspend fun startStillStored(pk: String, position: Int, url: String): Boolean =
        isLive(pk) && storedItem(pk, position)?.takeIf { it.type == MediaType.VIDEO }?.videoUrl == url

    /** N-M1: the start of item [position] out now ([Visit.startsOut]) from another link than [source] plays; null when none. */
    private fun Visit.startOtherThan(position: Int, source: VideoSource): StartOut? {
        val play = source as? VideoSource.Play ?: return null
        return startsOut[position]?.takeIf { it.url != play.uri.toString() }
    }

    /**
     * R25: [item]'s link was refused ahead (or it has none). The visit's one renewal, by [renewal]'s rule: the first call that
     * needs it sends it, a later one shares what it brought. A renewal that brings this item no other link (it was spent on
     * the links stored now) or that failed stops the queue; one that brings a new link loads it, and the queue goes on.
     */
    private suspend fun renewAhead(media: MediaEntity, position: Int, item: CarouselItemEntity, visit: Visit, what: String): ImageAhead {
        val renewed = when (val renewal = renewal(media, visit, RENEW)) {
            is Renewal.Failed -> return ImageAhead.Stop
            is Renewal.Items -> renewal.items.at(position)
        }
        // R20's new shape has another type here (or no item): nothing to fetch for it, and the rest has fresh links.
        if (renewed == null || renewed.type != item.type) return ImageAhead.Skipped
        if (renewed.imageUrl == item.imageUrl) return ImageAhead.Stop
        return when (val second = download(media.pk, position, renewed.imageUrl, visit, what)) {
            Download.Replaced -> ImageAhead.Skipped
            else -> second.toAhead()
        }
    }

    private suspend fun loadVideo(media: MediaEntity, position: Int, visit: Visit, forceRefresh: Boolean): VideoSource {
        // Not a carousel: no items to fill, so no request (a reel's link is the video resolver's).
        if (media.type != MediaType.CAROUSEL) return VideoSource.Unavailable(CANT_LOAD)
        val pk = media.pk
        val key = "${pk}_$position"
        val item = storedItem(pk, position) ?: when (val renewal = renewal(media, visit, FILL)) {
            is Renewal.Failed -> return VideoSource.Unavailable(renewal.message)
            is Renewal.Items -> renewal.items.at(position) ?: return VideoSource.Unavailable(CANT_LOAD)
        }
        if (item.type != MediaType.VIDEO) return VideoSource.Unavailable(CANT_LOAD)
        // R3: Mock mode plays the bundled clip; the fake library's links name nothing a player can open.
        sampleClip?.let { return VideoSource.Play(it, key) }
        // R26, R27: the CDN refused this very link as running out (403, 410) when the item's start was written ahead in this
        // visit, as it would have refused the player: it is not asked again, the visit's renewal is (as after the player's own).
        val refusedAhead = item.videoUrl?.let { it in visit.refused } == true
        if (!forceRefresh) {
            if (!refusedAhead && !item.videoLinkNeedsRefresh(now())) return VideoSource.Play(checkNotNull(item.videoUrl).toUri(), key)
            // The bytes are on disk, so the link that names them is beside the point (R78).
            item.cachedPlay(key)?.let { return it }
        }
        val renewed = when (val renewal = renewal(media, visit, RENEW)) {
            // R9, the reel resolver's table: cached bytes still play when Instagram could not be asked or reached (no session, a
            // refusal, offline), not when it answered against the item (a login or challenge, a rate limit, gone, anything else).
            is Renewal.Failed -> {
                val cached = if (renewal.cachedMayPlay) item.cachedPlay(key) else null
                return cached ?: VideoSource.Unavailable(renewal.message)
            }
            is Renewal.Items -> renewal.items.at(position)
        }
        val url = renewed?.takeIf { it.type == MediaType.VIDEO }?.videoUrl ?: return VideoSource.Unavailable(CANT_LOAD)
        // The player (or the start written ahead) was refused this very link: a renewal that brings it back has nothing better.
        if ((forceRefresh || refusedAhead) && url == item.videoUrl) return VideoSource.Unavailable(CANT_LOAD)
        return VideoSource.Play(url.toUri(), key)
    }

    /**
     * The [visit]'s one `mediaInfo`: sent by the first call that needs it, shared by every later one. It is spent from the
     * moment it is tried: a call cancelled on the way may already have sent it, so the visit sends no other.
     */
    private suspend fun renewal(media: MediaEntity, visit: Visit, purpose: String): Renewal = visit.lock.withLock {
        visit.renewal?.let { return@withLock it }
        visit.renewal = SPENT
        renew(media, purpose).also { visit.renewal = it }
    }

    private suspend fun renew(media: MediaEntity, purpose: String): Renewal {
        // A post no longer in the library (Delete library ran while it was open, or a sync found it unsaved) needs no items:
        // nothing is asked.
        if (!isLive(media.pk)) return SPENT
        val outcome = info.fetch(media.pk)
        log?.invoke("$purpose: ${outcome.summary()}")
        return when (outcome) {
            is PacedMediaInfo.Outcome.Fresh -> store(media.pk, outcome.media.carouselItems)
            PacedMediaInfo.Outcome.Gone -> Renewal.Failed(GONE)
            is PacedMediaInfo.Outcome.Failed -> Renewal.Failed(outcome.forItem(), cachedMayPlay = outcome.reason in NOT_ASKED)
        }
    }

    /**
     * The post's fresh items, in place of whatever was stored. A reply without them stores nothing and keeps the stored ones.
     * When the post's row went while the request was out (Delete library), the foreign key refuses the write: that throws, into
     * the public call's guard, which answers "Can't load this item"; the visit stays spent. Items of another shape than the
     * stored ones (R20) take the post's cached item media with them, once they are stored.
     */
    private suspend fun store(pk: String, remote: List<RemoteCarouselItem>?): Renewal {
        // R2 and R4: the reply does not say what the items are (no carousel_media, an empty one, an unreadable child). An empty
        // list is a shape blip far more often than a carousel without items, so it never replaces the stored ones with nothing.
        if (remote.isNullOrEmpty()) return Renewal.Failed(CANT_LOAD)
        val fetchedAt = now()
        val items = remote.map { it.toEntity(pk).withAssumedLifetime(fetchedAt) }.sortedBy { it.position }
        val reshaped = itemShapeChanged(dao.items(pk), items)
        dao.replace(pk, items)
        if (reshaped) dropItemMedia(pk)
        return Renewal.Items(items)
    }

    /** R20's housekeeping, off the caller's thread: the items are stored, so a file or a cached video left behind only takes space. */
    private suspend fun dropItemMedia(pk: String) {
        try {
            withContext(ioDispatcher) { itemEviction.evict(listOf(pk)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Intentionally ignored, as the sync's eviction is.
        }
    }

    /**
     * Downloads [url] on the CDN lane into the image store. An empty [url] (Instagram sent no image), or one the CDN refused
     * earlier in the [visit] (R25), is refused without a request. A failure is logged under [what] (an on-demand image, or a
     * prefetch).
     */
    private suspend fun download(pk: String, position: Int, url: String, visit: Visit, what: String = IMAGE_LOG): Download {
        if (url.isEmpty()) return failedImage(Download.Refused, what, "no link")
        if (url in visit.refused) return failedImage(Download.Refused, what, "refused before")
        val bytes = try {
            pacer.cdn { fetcher.fetch(url) }
        } catch (e: CdnRateLimited) {
            return failedImage(Download.Limited, what, "cdn limit")
        } catch (e: IOException) {
            return failedImage(Download.Offline, what, "offline")
        }
        if (bytes == null) {
            visit.refused += url
            return failedImage(Download.Refused, what, "refused")
        }
        // A post deleted (Delete library) or unsaved (a sync's reconcile, whose eviction may already have run) while its image
        // was on the way keeps no file behind: nothing would remove it later. The check and the write are not one step, so a
        // delete in between can still leave one; it only takes space until the next Delete library.
        if (!isLive(pk)) return failedImage(Download.Gone, what, "post gone")
        // P2: the post's items were replaced while this downloaded (a renewal or a sync, whose R20 drop may already have removed
        // the post's pictures). Bytes of a link no longer stored at this position may be another slide's: none is written. The
        // same gap as above remains between this check and the write.
        if (storedItem(pk, position)?.imageUrl != url) return failedImage(Download.Replaced, what, "link replaced")
        return Download.Stored(withContext(ioDispatcher) { images.write(pk, position, bytes) })
    }

    /** True while the post [pk] is in the library: stored, and not marked removed. Every viewer list shows only such posts. */
    private suspend fun isLive(pk: String): Boolean = mediaDao.byPks(listOf(pk)).singleOrNull()?.let { it.removedAt == null } == true

    private fun failedImage(download: Download, what: String, reason: String): Download {
        log?.invoke("$what failed ($reason)")
        return download
    }

    private suspend fun storedItem(pk: String, position: Int): CarouselItemEntity? = dao.items(pk).at(position)

    /** The item's stored link when all its bytes are cached under [key], else null. Off the caller's thread, like the reel resolver's. */
    private suspend fun CarouselItemEntity.cachedPlay(key: String): VideoSource? {
        val url = videoUrl ?: return null
        return if (withContext(ioDispatcher) { isVideoCached(key) }) VideoSource.Play(url.toUri(), key) else null
    }

    /** What the visit's one request brought: the post's items, stored, or why there are none. */
    internal sealed interface Renewal {
        data class Items(val items: List<CarouselItemEntity>) : Renewal

        /** [cachedMayPlay]: Instagram could not be asked or reached, so a video item's cached bytes may still play (R9). */
        data class Failed(val message: String, val cachedMayPlay: Boolean = false) : Renewal
    }

    private sealed interface Download {
        data class Stored(val path: String) : Download

        /** The CDN refused the link (403, 404, 410), now or earlier in the visit, or there is none: a renewal may bring a better one. */
        data object Refused : Download

        data object Offline : Download

        /** The CDN is limiting requests (429): a renewal would not help. */
        data object Limited : Download

        /** The post left the library while its picture downloaded. */
        data object Gone : Download

        /** P2: the item's stored link changed while it downloaded; the bytes were not written. */
        data object Replaced : Download
    }

    private fun Download.toImage(): ItemImage = when (this) {
        is Download.Stored -> ItemImage.File(path)
        Download.Offline -> ItemImage.Failed(OFFLINE)
        Download.Refused, Download.Limited, Download.Gone, Download.Replaced -> ItemImage.Failed(CANT_LOAD)
    }

    /** For the queue of pictures fetched ahead: a picture shown, an item to pass, or nothing more in this visit (P1). */
    private fun Download.toAhead(): ImageAhead = when (this) {
        is Download.Stored -> ImageAhead.Shown(path)
        Download.Replaced -> ImageAhead.Skipped
        Download.Refused, Download.Offline, Download.Limited, Download.Gone -> ImageAhead.Stop
    }

    private companion object {
        const val CANT_LOAD = "Can't load this item"
        const val OFFLINE = "Offline: this item isn't cached yet"
        const val GONE = "This item is no longer available on Instagram"
        const val FILL = "carousel fill"
        const val RENEW = "carousel renew"
        const val IMAGE_LOG = "carousel image"
        const val PREFETCH_LOG = "carousel prefetch"
        const val POSTER_LOG = "carousel poster"
        const val START_LOG = "carousel start"

        /** What a visit holds while its request is out, and keeps if that call never finishes. */
        val SPENT = Renewal.Failed(CANT_LOAD)

        /** The failures in which Instagram was not asked or not reached (R9: the reel resolver's cache fallback table). */
        val NOT_ASKED = setOf(PacedMediaInfo.Reason.NO_SESSION, PacedMediaInfo.Reason.REFUSED, PacedMediaInfo.Reason.OFFLINE)

        fun List<CarouselItemEntity>.at(position: Int): CarouselItemEntity? = firstOrNull { it.position == position }

        /** R78, as for reel links: a renewed link that names no expiry is assumed good for an hour, so it costs no request each visit. */
        fun CarouselItemEntity.withAssumedLifetime(fetchedAt: Long): CarouselItemEntity =
            if (videoUrl == null || videoUrlExpiresAt != null) this
            else copy(videoUrlExpiresAt = fetchedAt + RealVideoSourceResolver.ASSUMED_LIFETIME_MS)

        /** The failure in this item's words. */
        fun PacedMediaInfo.Outcome.Failed.forItem(): String = when (reason) {
            PacedMediaInfo.Reason.NO_SESSION, PacedMediaInfo.Reason.NEEDS_ATTENTION, PacedMediaInfo.Reason.RATE_LIMITED -> message
            PacedMediaInfo.Reason.REFUSED -> "Can't load this item right now: $message"
            PacedMediaInfo.Reason.OFFLINE -> OFFLINE
            PacedMediaInfo.Reason.UNEXPECTED -> CANT_LOAD
        }

        /** For the debug log: what came back, never a link or a pk. */
        fun PacedMediaInfo.Outcome.summary(): String = when (this) {
            is PacedMediaInfo.Outcome.Fresh -> "fresh (${media.carouselItems?.size ?: "no"} items)"
            PacedMediaInfo.Outcome.Gone -> "gone"
            is PacedMediaInfo.Outcome.Failed -> reason.name.lowercase()
        }
    }
}
