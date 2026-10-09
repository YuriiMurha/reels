package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
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
    /** Where a reply to [WebGraphQl.SAVED_COLLECTIONS] holds the account's collections (spec 2026-10-09 §3.1). */
    const val COLLECTIONS_ROOT = "data.viewer.collections_unified_with_auto_collections"

    /** What the website puts before some JSON replies so they can't be run as a script. It is not part of the JSON. */
    private const val FOR_LOOP_GUARD = "for (;;);"

    /** HTTP statuses whose non-JSON reply fact STALE reads as a stale query ([isStale]). */
    private val STALE_HTTP_CODES = setOf(400, 404)

    /** [body] without a leading [FOR_LOOP_GUARD]. */
    fun withoutGuard(body: String): String = body.trimStart().removePrefix(FOR_LOOP_GUARD)

    /**
     * Task 1 fact AUTO (assumed; verify on the phone): the website's automatic collections ("All posts", audio) carry a
     * `collection_id` that is not a number (`ALL_MEDIA_AUTO_COLLECTION`), the owner's own collections one of digits only. A
     * collection is never told by its display name.
     */
    fun isUserCollectionId(id: String): Boolean = isPk(id)

    /** The [COLLECTIONS_ROOT] object of [json], or null when the reply has none. */
    fun collectionsRoot(json: JsonObject): JsonObject? =
        ((json["data"] as? JsonObject)?.get("viewer") as? JsonObject)?.get("collections_unified_with_auto_collections") as? JsonObject

    /**
     * Task 1 fact STALE (assumed; verify on the phone): how the website answers a query whose doc id it no longer runs. Either
     * a 2xx JSON reply with a non-empty `errors` array and no [COLLECTIONS_ROOT] (whatever its `status`), or a 400/404 that
     * is not JSON at all (which [classifyReply] alone calls `ShapeChanged("http.<code>")`). [classified] is [classifyReply]'s
     * answer and [json] the reply's JSON after the guard. Only a shape change can be stale: what [classifyReply] calls a
     * challenge, a rate limit, a logout or a network problem keeps that meaning.
     */
    private fun isStale(reply: RawReply, classified: InstagramException?, json: JsonObject?): Boolean = when {
        classified != null && classified !is ShapeChanged -> false
        reply.code in STALE_HTTP_CODES -> json == null && (classified as? ShapeChanged)?.fieldPath == "http.${reply.code}"
        reply.code in 200..299 -> json != null && collectionsRoot(json) == null && (json["errors"] as? JsonArray)?.isNotEmpty() == true
        else -> false
    }

    /**
     * The failure a reply to [WebGraphQl.SAVED_COLLECTIONS] signals, or null when [collectionsGraphQl] may read it. The rule of
     * every reply ([classifyReply]) applies to the body after a leading `for (;;);`, so a challenge, a rate limit or a logout
     * keeps its meaning. Fact STALE's reply ([isStale]) is a [InstagramException.StaleQuery], never a shape change: only a
     * new doc id can fix it. A JSON reply without the [COLLECTIONS_ROOT] that is not stale is `ShapeChanged(COLLECTIONS_ROOT)`.
     */
    fun classifyGraphQl(reply: RawReply): InstagramException? {
        val guarded = reply.body?.let(::withoutGuard)
        val unguarded = if (guarded == reply.body) reply else RawReply(reply.code, reply.contentType, guarded, reply.redirected)
        val classified = classifyReply(unguarded)
        val json = guarded?.let(::parseObject)
        if (isStale(reply, classified, json)) return InstagramException.StaleQuery(WebGraphQl.SAVED_COLLECTIONS.friendlyName)
        if (classified != null) return classified
        return if (json != null && collectionsRoot(json) != null) null else ShapeChanged(COLLECTIONS_ROOT)
    }

    /** The JSON of a reply to [WebGraphQl.SAVED_COLLECTIONS], after the guard, or the failure [classifyGraphQl] finds in it. */
    fun graphQlJsonOrThrow(reply: RawReply): JsonObject {
        classifyGraphQl(reply)?.let { throw it }
        return parseObject(withoutGuard(reply.body!!)) ?: throw ShapeChanged("$")
    }

    /**
     * One page of the account's own collections from a reply to [WebGraphQl.SAVED_COLLECTIONS]: the nodes of
     * [COLLECTIONS_ROOT]`.edges`, in order, less the automatic ones ([isUserCollectionId]). Paths in a [ShapeChanged] are
     * relative to the root. A name is kept as the website sends it, an empty one too.
     */
    fun collectionsGraphQl(json: JsonObject): Page<RemoteCollection> {
        val root = collectionsRoot(json) ?: throw ShapeChanged(COLLECTIONS_ROOT)
        val edges = root["edges"] as? JsonArray ?: throw ShapeChanged("edges")
        val collections = edges.mapIndexedNotNull { i, element ->
            val edge = element as? JsonObject ?: throw ShapeChanged("edges[$i]")
            val node = edge["node"] as? JsonObject ?: throw ShapeChanged("edges[$i].node")
            val id = node.idString("collection_id") ?: throw ShapeChanged("edges[$i].node.collection_id")
            if (!isUserCollectionId(id)) return@mapIndexedNotNull null
            RemoteCollection(
                id = id,
                name = node.string("collection_name") ?: throw ShapeChanged("edges[$i].node.collection_name"),
                // Only a cover: a pk that isn't plain digits means no cover, not a failed page.
                coverMediaPk = (node["cover_media"] as? JsonObject)?.idString("pk")?.takeIf(::isPk),
            )
        }
        return Page(collections, graphQlCursor(root))
    }

    /** Spec 6.3 Q4 for GraphQL: a page that says nothing about more pages is a shape change, never "last page" (P5). */
    private fun graphQlCursor(root: JsonObject): String? {
        val info = root["page_info"] as? JsonObject ?: throw ShapeChanged("page_info")
        // A real JSON boolean only: the string "false" must not read as "last page".
        val more = (info["has_next_page"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw ShapeChanged("page_info.has_next_page")
        if (!more) return null
        return info.string("end_cursor")?.takeIf { it.isNotEmpty() } ?: throw ShapeChanged("page_info.end_cursor")
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
