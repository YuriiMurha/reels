package io.github.yuriimurha.reels.data.media

import android.net.Uri
import androidx.core.net.toUri
import io.github.yuriimurha.reels.data.db.CarouselItemDao
import io.github.yuriimurha.reels.data.db.CarouselItemEntity
import io.github.yuriimurha.reels.data.db.MediaDao
import io.github.yuriimurha.reels.data.db.MediaEntity
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
 * offline (R1). A video item plays from its link with its bytes cached under `{pk}_{position}` (spec §3.2).
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
) {
    /**
     * One visit of a post, from the moment the viewer shows it until it leaves it: the visit's one `mediaInfo`, and what it
     * brought, which every later call of the visit shares. The viewer makes a new one each time the post is shown again.
     */
    class Visit {
        internal val lock = Mutex()
        internal var renewal: Renewal? = null
    }

    /** Stored items; when none, one paced `mediaInfo` fills them (once a [visit]). Not a carousel: [ItemsResult.CoverOnly] (null). */
    suspend fun items(media: MediaEntity, visit: Visit): ItemsResult = try {
        loadItems(media, visit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ItemsResult.CoverOnly(CANT_LOAD)
    }

    /** The image file of item [position], downloaded on demand; a refused or missing link renews the post's links once a [visit]. */
    suspend fun image(media: MediaEntity, position: Int, visit: Visit): ItemImage = try {
        loadImage(media, position, visit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ItemImage.Failed(CANT_LOAD)
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
        // A post no longer in the library (Delete library ran while it was open) has nowhere to keep items: nothing is asked.
        if (mediaDao.byPks(listOf(media.pk)).isEmpty()) return SPENT
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
     * the public call's guard, which answers "Can't load this item"; the visit stays spent.
     */
    private suspend fun store(pk: String, remote: List<RemoteCarouselItem>?): Renewal {
        // R4: the reply does not say what the items are (no carousel_media, an unreadable child).
        if (remote.isNullOrEmpty()) return Renewal.Failed(CANT_LOAD)
        val fetchedAt = now()
        val items = remote.map { it.toEntity(pk).withAssumedLifetime(fetchedAt) }.sortedBy { it.position }
        dao.replace(pk, items)
        return Renewal.Items(items)
    }

    /** Downloads [url] on the CDN lane into the image store. An empty [url] (Instagram sent no image) is refused without a request. */
    private suspend fun download(pk: String, position: Int, url: String): Download {
        if (url.isEmpty()) return failedImage(Download.Refused, "no link")
        val bytes = try {
            pacer.cdn { fetcher.fetch(url) }
        } catch (e: CdnRateLimited) {
            return failedImage(Download.Failed, "cdn limit")
        } catch (e: IOException) {
            return failedImage(Download.Offline, "offline")
        }
        if (bytes == null) return failedImage(Download.Refused, "refused")
        // A post deleted while its image was on the way (Delete library) keeps no file behind. The check and the write are not
        // one step, so a delete in between can still leave one; it only takes space until the next Delete library.
        if (mediaDao.byPks(listOf(pk)).isEmpty()) return failedImage(Download.Failed, "post gone")
        return Download.Stored(withContext(ioDispatcher) { images.write(pk, position, bytes) })
    }

    private fun failedImage(download: Download, reason: String): Download {
        log?.invoke("carousel image failed ($reason)")
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
