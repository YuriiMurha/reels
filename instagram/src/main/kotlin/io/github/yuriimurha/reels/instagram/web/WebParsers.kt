package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException.ShapeChanged
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
                // Only a cover: a pk that isn't plain digits means no cover, not a failed page.
                coverMediaPk = (o["cover_media"] as? JsonObject)?.idString("pk")?.takeIf(::isPk),
            )
        }
        return Page(collections, nextCursor(json))
    }

    fun savedPage(json: JsonObject): Page<RemoteMedia> {
        val media = json.items().mapIndexedNotNull { i, element ->
            val wrapper = element as? JsonObject ?: throw ShapeChanged("items[$i]")
            // Only an explicit `"media": null` means Instagram can no longer show the item (R60): nothing to store.
            // A missing key or any other type is a shape change; skipping it would let a FULL reconcile delete the item.
            val raw = wrapper["media"]
            if (raw is JsonNull) return@mapIndexedNotNull null
            media(raw as? JsonObject ?: throw ShapeChanged("items[$i].media"), "items[$i].media")
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
        // A real JSON boolean only: the string "false" must not read as "last page".
        val more = (json["more_available"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw ShapeChanged("more_available")
        if (!more) return null
        return json.idString("next_max_id") ?: throw ShapeChanged("next_max_id")
    }

    private fun media(o: JsonObject, path: String): RemoteMedia {
        val pk = o.idString("pk")?.takeIf(::isPk) ?: throw ShapeChanged("$path.pk")
        val code = o.string("code") ?: throw ShapeChanged("$path.code")
        val type = when (o.int("media_type")) {
            1 -> MediaType.IMAGE
            2 -> if (o.string("product_type") == "clips") MediaType.REEL else MediaType.VIDEO
            8 -> MediaType.CAROUSEL
            else -> throw ShapeChanged("$path.media_type")
        }
        val user = o["user"] as? JsonObject ?: throw ShapeChanged("$path.user")
        val author = user.string("username") ?: throw ShapeChanged("$path.user.username")
        val takenAt = o.long("taken_at")?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
            ?: throw ShapeChanged("$path.taken_at")
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
            takenAt = takenAt,
            width = o.int("original_width") ?: thumb?.width ?: 0,
            height = o.int("original_height") ?: thumb?.height ?: 0,
            carouselCount = if (type == MediaType.CAROUSEL) o.int("carousel_media_count") ?: carousel?.size else null,
            thumbnailUrl = thumb?.url.orEmpty(),
            videoUrl = videoUrl,
            videoUrlExpiresAt = videoUrl?.let(MediaLinks::expiresAt),
            savedCollectionIds = savedCollectionIds(o),
        )
    }

    /** Media pks are plain digits (spec 6): a float literal such as 3.1E18, or text, means the shape changed. */
    private val PK = Regex("[0-9]{1,30}")

    private fun isPk(value: String): Boolean = PK.matches(value)

    /** Null ("the response doesn't say") unless every entry is a string: a shorter list would read as "not saved there". */
    private fun savedCollectionIds(o: JsonObject): List<String>? {
        val ids = o["saved_collection_ids"] as? JsonArray ?: return null
        val strings = ids.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return if (strings.any { it == null }) null else strings.filterNotNull()
    }
}
