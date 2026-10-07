package io.github.yuriimurha.reels.data.media

import android.net.Uri
import androidx.core.net.toUri
import io.github.yuriimurha.reels.R
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.MediaType

/** What the viewer does with a video item (spec 8). */
sealed interface VideoSource {
    /** Play [uri]; [cacheKey] (the media pk) makes a refreshed link still hit cached bytes (spec 8.3). */
    data class Play(val uri: Uri, val cacheKey: String) : VideoSource

    /** Show the thumbnail, [message] and Open on Instagram (spec 8.5). */
    data class Unavailable(val message: String) : VideoSource
}

interface VideoSourceResolver {
    /** Null for items that aren't videos. [forceRefresh] skips the freshness check (one retry after a 403/410). */
    suspend fun resolve(media: MediaEntity, forceRefresh: Boolean = false): VideoSource?
}

val MediaEntity.isVideo: Boolean get() = type == MediaType.REEL || type == MediaType.VIDEO

/**
 * True when playing this item needs a new link: it has none, nobody knows when it expires, or it expires within
 * [RealVideoSourceResolver.FRESH_MARGIN_MS] of [now] (a link exactly that far away is refreshed; "fresh" means strictly more).
 * A link about to run out would die mid-playback (spec 8.2). Shared by the resolver and the viewer's prefetch, so both
 * mean the same.
 */
fun MediaEntity.videoLinkNeedsRefresh(now: Long): Boolean {
    val expiresAt = videoUrlExpiresAt
    return videoUrl == null || expiresAt == null || expiresAt - now <= RealVideoSourceResolver.FRESH_MARGIN_MS
}

/** Every fake video plays the bundled synthetic clip. */
class FakeVideoSourceResolver(private val packageName: String) : VideoSourceResolver {
    override suspend fun resolve(media: MediaEntity, forceRefresh: Boolean): VideoSource? =
        if (media.isVideo) VideoSource.Play("android.resource://$packageName/${R.raw.sample_clip}".toUri(), media.pk) else null
}
