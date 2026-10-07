package io.github.yuriimurha.reels.instagram.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

internal data class ImageCandidate(val url: String, val width: Int, val height: Int)

/** How the adapter reads Instagram's media links (spec 4.1): candidate choice and CDN link expiry. */
internal object MediaLinks {
    /** Spec 14: thumbnails of about 720 px keep storage small and still look sharp in a two-column grid. */
    const val THUMBNAIL_MIN_WIDTH = 720

    fun imageCandidates(o: JsonObject): List<ImageCandidate> {
        val candidates = (o["image_versions2"] as? JsonObject)?.get("candidates") as? JsonArray ?: return emptyList()
        return candidates.mapNotNull { element ->
            val c = element as? JsonObject ?: return@mapNotNull null
            val url = c.string("url") ?: return@mapNotNull null
            ImageCandidate(url, c.int("width") ?: 0, c.int("height") ?: 0)
        }
    }

    /** The narrowest candidate at least [THUMBNAIL_MIN_WIDTH] wide, else the widest one. */
    fun chooseThumbnail(candidates: List<ImageCandidate>): ImageCandidate? =
        candidates.filter { it.width >= THUMBNAIL_MIN_WIDTH }.minByOrNull { it.width } ?: candidates.maxByOrNull { it.width }

    /** Instagram CDN links carry their expiry as hex Unix seconds in `oe` (spec 6.3 Q7). Null when absent, malformed or out of range. */
    fun expiresAt(url: String): Instant? =
        url.toHttpUrlOrNull()?.queryParameter("oe")?.toLongOrNull(16)?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
}
