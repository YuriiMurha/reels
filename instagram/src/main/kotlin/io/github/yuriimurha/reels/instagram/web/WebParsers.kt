package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException.ShapeChanged
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

/**
 * Instagram web JSON to adapter models (spec 6). Unknown keys are ignored; a missing required field throws
 * ShapeChanged with its path. Shapes follow instaloader/instagrapi until the M3 spike confirms them.
 */
internal object WebParsers {
    fun collectionsPage(json: JsonObject): Page<RemoteCollection> {
        val items = json.items()
        val collections = items.mapIndexedNotNull { i, element ->
            val o = element as? JsonObject ?: throw ShapeChanged("items[$i]")
            if (o.string("collection_type") != "MEDIA") return@mapIndexedNotNull null
            RemoteCollection(
                id = o.idString("collection_id") ?: throw ShapeChanged("items[$i].collection_id"),
                name = o.string("collection_name") ?: throw ShapeChanged("items[$i].collection_name"),
                coverMediaPk = (o["cover_media"] as? JsonObject)?.idString("pk"),
            )
        }
        return Page(collections, nextCursor(json))
    }

    fun savedPage(json: JsonObject): Page<RemoteMedia> {
        val media = json.items().mapIndexedNotNull { i, element ->
            val wrapper = element as? JsonObject ?: throw ShapeChanged("items[$i]")
            // An item Instagram can no longer show has no media object: nothing to store, not a shape change.
            val m = wrapper["media"] as? JsonObject ?: return@mapIndexedNotNull null
            media(m, "items[$i].media")
        }
        return Page(media, nextCursor(json))
    }

    fun mediaInfo(json: JsonObject): RemoteMedia? {
        val first = json.items().firstOrNull() ?: return null
        return media(first as? JsonObject ?: throw ShapeChanged("items[0]"), "items[0]")
    }

    private fun JsonObject.items(): JsonArray = this["items"] as? JsonArray ?: throw ShapeChanged("items")

    /** Spec 6.3 Q4. A page that says nothing about more pages is a shape change, never "last page" (P5). */
    private fun nextCursor(json: JsonObject): String? {
        val more = (json["more_available"] as? JsonPrimitive)?.booleanOrNull ?: throw ShapeChanged("more_available")
        if (!more) return null
        return json.idString("next_max_id") ?: throw ShapeChanged("next_max_id")
    }

    private fun media(o: JsonObject, path: String): RemoteMedia {
        val pk = o.idString("pk") ?: throw ShapeChanged("$path.pk")
        val code = o.string("code") ?: throw ShapeChanged("$path.code")
        val type = when (o.int("media_type")) {
            1 -> MediaType.IMAGE
            2 -> if (o.string("product_type") == "clips") MediaType.REEL else MediaType.VIDEO
            8 -> MediaType.CAROUSEL
            else -> throw ShapeChanged("$path.media_type")
        }
        val user = o["user"] as? JsonObject ?: throw ShapeChanged("$path.user")
        val author = user.string("username") ?: throw ShapeChanged("$path.user.username")
        val takenAt = o.long("taken_at") ?: throw ShapeChanged("$path.taken_at")
        val carousel = o["carousel_media"] as? JsonArray
        val imageSource = if (o["image_versions2"] is JsonObject) o else carousel?.firstOrNull() as? JsonObject
        val thumb = imageSource?.let { MediaLinks.chooseThumbnail(MediaLinks.imageCandidates(it)) }
        val videoUrl = ((o["video_versions"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("url")
        return RemoteMedia(
            pk = pk,
            code = code,
            type = type,
            author = author,
            caption = (o["caption"] as? JsonObject)?.string("text"),
            takenAt = Instant.ofEpochSecond(takenAt),
            width = o.int("original_width") ?: thumb?.width ?: 0,
            height = o.int("original_height") ?: thumb?.height ?: 0,
            carouselCount = if (type == MediaType.CAROUSEL) o.int("carousel_media_count") ?: carousel?.size else null,
            thumbnailUrl = thumb?.url.orEmpty(),
            videoUrl = videoUrl,
            videoUrlExpiresAt = videoUrl?.let(MediaLinks::expiresAt),
            savedCollectionIds = (o["saved_collection_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }
}
