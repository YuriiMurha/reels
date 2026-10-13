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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * A carousel's items on demand (spec 2026-10-12 §3.3). Stored items are used as they are. A post with none (synced before
 * items were kept, or a reply that did not carry them, R2/R4) gets them from one `mediaInfo` ([PacedMediaInfo]: the
 * interactive lane, under the session gates of R76/R79). A link that fails (an image the CDN refuses, an item with no image,
 * a video link that is stale, missing or refused by the player) renews the whole post's links with the same request.
 *
 * Pacing: a [Visit] allows ONE such request, the fill and the renewal together, whatever came of it (a failure, a cancelled
 * call): every other call in the visit uses what that one brought. Nothing here runs during a sync. Images download through
 * the cookieless CDN [MediaFetcher] on the Pacer's CDN lane, like the sync's thumbnails, into [images], so a viewed item shows
 * offline (R1). [prefetchImage] downloads an item's picture the same way before the owner swipes to it, and asks Instagram
 * nothing (R23: the viewer fetches a carousel's other pictures ahead, one at a time). A video item plays from its link with its
 * bytes cached under `{pk}_{position}` (spec §3.2).
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
         * item asked for while its prefetch is out waits for that download and then finds its file. Nothing to do with the
         * visit's request: a prefetch never takes [lock] and never reads or writes [renewal].
         */
        private val downloads = ConcurrentHashMap<Int, Mutex>()

        internal fun downloadOf(position: Int): Mutex = downloads.computeIfAbsent(position) { Mutex() }
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
     * is loaded as if there had been no prefetch.
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
     * pictures, one at a time). Only an IMAGE item past the cover (item 0 shows the stored cover, R10) with a stored link, of a
     * post still in the library,
     * through the CDN lane with the same fetcher and store as [image]. It answers the item's stored file (already there, or just
     * downloaded), else null.
     *
     * Pacing: it never fills, never renews, never sends a `mediaInfo`, and never spends or reads the [visit]'s request: it
     * shares only the visit's per-item download lock with [image], so the two never download one item twice. Any failure (a
     * refused or missing link, offline, the CDN's 429, a post gone, an unsafe key) is quiet: null, and [image] deals with the
     * item as it would have when the owner gets there. Throws only [CancellationException].
     */
    suspend fun prefetchImage(media: MediaEntity, position: Int, visit: Visit): String? = try {
        loadAhead(media, position, visit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * A playable source for the VIDEO item at [position], keyed `{pk}_{position}`: its stored link while fresh (by the reel
     * links' rule) or while its bytes are all cached (R78), else the link one renewal a [visit] brings. [forceRefresh] is the
     * player's refused link (a 403 or 410): only a different link is worth playing then.
     */
    suspend fun video(media: MediaEntity, position: Int, visit: Visit, forceRefresh: Boolean = false): VideoSource = try {
        loadVideo(media, position, visit, forceRefresh)
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

    private suspend fun loadImageNow(media: MediaEntity, position: Int, visit: Visit): ItemImage {
        val pk = media.pk
        withContext(ioDispatcher) { images.path(pk, position) }?.let { return ItemImage.File(it) }
        val item = storedItem(pk, position) ?: when (val renewal = renewal(media, visit, FILL)) {
            is Renewal.Failed -> return ItemImage.Failed(renewal.message)
            is Renewal.Items -> renewal.items.at(position) ?: return ItemImage.Failed(CANT_LOAD)
        }
        val first = download(pk, position, item.imageUrl)
        if (first != Download.Refused) return first.toImage()
        // The CDN refused the link (it expired), or there is none: the post's links are renewed, once a visit.
        val renewed = when (val renewal = renewal(media, visit, RENEW)) {
            is Renewal.Failed -> return ItemImage.Failed(renewal.message)
            is Renewal.Items -> renewal.items.at(position)
        }
        // Only a new link is worth a download: the one just refused would be refused again.
        if (renewed == null || renewed.imageUrl == item.imageUrl) return ItemImage.Failed(CANT_LOAD)
        return download(pk, position, renewed.imageUrl).toImage()
    }

    /** [prefetchImage]: the stored items and the stored link only; whatever fails, nothing more is tried. */
    private suspend fun loadAhead(media: MediaEntity, position: Int, visit: Visit): String? {
        // Not a carousel: no items. Item 0: the stored cover shows it (R10). Neither is downloaded ahead.
        if (media.type != MediaType.CAROUSEL || position <= 0) return null
        val pk = media.pk
        // Stored items only, never the fill: with none stored, or none at this position (past the last item), nothing is asked.
        val item = storedItem(pk, position)?.takeIf { it.type == MediaType.IMAGE } ?: return null
        return visit.downloadOf(position).withLock {
            withContext(ioDispatcher) { images.path(pk, position) }?.let { return@withLock it }
            // As for [download]'s own check after the bytes: a post deleted or unsaved gets no download at all.
            if (!isLive(pk)) return@withLock null
            (download(pk, position, item.imageUrl, PREFETCH_LOG) as? Download.Stored)?.path
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
        if (!forceRefresh) {
            if (!item.videoLinkNeedsRefresh(now())) return VideoSource.Play(checkNotNull(item.videoUrl).toUri(), key)
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
        // The player was refused this very link: a renewal that brings it back has nothing better to offer.
        if (forceRefresh && url == item.videoUrl) return VideoSource.Unavailable(CANT_LOAD)
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
     * Downloads [url] on the CDN lane into the image store. An empty [url] (Instagram sent no image) is refused without a request.
     * A failure is logged under [what] (an on-demand image, or a prefetch).
     */
    private suspend fun download(pk: String, position: Int, url: String, what: String = IMAGE_LOG): Download {
        if (url.isEmpty()) return failedImage(Download.Refused, what, "no link")
        val bytes = try {
            pacer.cdn { fetcher.fetch(url) }
        } catch (e: CdnRateLimited) {
            return failedImage(Download.Failed, what, "cdn limit")
        } catch (e: IOException) {
            return failedImage(Download.Offline, what, "offline")
        }
        if (bytes == null) return failedImage(Download.Refused, what, "refused")
        // A post deleted (Delete library) or unsaved (a sync's reconcile, whose eviction may already have run) while its image
        // was on the way keeps no file behind: nothing would remove it later. The check and the write are not one step, so a
        // delete in between can still leave one; it only takes space until the next Delete library.
        if (!isLive(pk)) return failedImage(Download.Failed, what, "post gone")
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

        /** The CDN refused the link (403, 404, 410), or there is none: a renewal may bring a better one. */
        data object Refused : Download

        data object Offline : Download

        /** The CDN is limiting requests (429): a renewal would not help. */
        data object Failed : Download
    }

    private fun Download.toImage(): ItemImage = when (this) {
        is Download.Stored -> ItemImage.File(path)
        Download.Offline -> ItemImage.Failed(OFFLINE)
        Download.Refused, Download.Failed -> ItemImage.Failed(CANT_LOAD)
    }

    private companion object {
        const val CANT_LOAD = "Can't load this item"
        const val OFFLINE = "Offline: this item isn't cached yet"
        const val GONE = "This item is no longer available on Instagram"
        const val FILL = "carousel fill"
        const val RENEW = "carousel renew"
        const val IMAGE_LOG = "carousel image"
        const val PREFETCH_LOG = "carousel prefetch"

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
