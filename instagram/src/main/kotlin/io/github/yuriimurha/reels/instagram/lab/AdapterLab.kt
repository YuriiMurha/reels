package io.github.yuriimurha.reels.instagram.lab

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.ErrorClassifier
import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import io.github.yuriimurha.reels.instagram.web.classifyUnreadable
import io.github.yuriimurha.reels.instagram.web.getRaw
import io.github.yuriimurha.reels.instagram.web.idString
import io.github.yuriimurha.reels.instagram.web.sessionUserId
import io.github.yuriimurha.reels.instagram.web.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** The five endpoint calls the Adapter lab can make (spec 6.2). */
enum class LabCall { CURRENT_USER, COLLECTIONS, SAVED_ALL, SAVED_COLLECTION, MEDIA_INFO }

/**
 * One lab call's answer. [ids] are real ids held in memory only, to chain the next call; [toString] omits them. The raw
 * body is not here: [shape] and [scrubbedJson] are the only things derived from it.
 */
class LabResult(
    val call: LabCall,
    val httpCode: Int,
    /** `ok`, or the simple name of the InstagramException ErrorClassifier picked. */
    val classification: String,
    /** Non-null when the response was classified as a failure. */
    val error: InstagramException?,
    /** [ShapeDump] of the body, every identifying value redacted. */
    val shape: String,
    /** [Scrubber] output, pretty-printed; null when the body was not JSON. */
    val scrubbedJson: String?,
    val ids: LabIds,
) {
    override fun toString(): String =
        "LabResult(call=$call, httpCode=$httpCode, classification=$classification, " +
            "shape=<${shape.length} chars>, scrubbedJson=${if (scrubbedJson == null) "none" else "<${scrubbedJson.length} chars>"}, ids=$ids)"
}

/** The ids a lab call revealed, for the next call. Real values: keep them in memory, never print or save them. */
class LabIds(val firstCollectionId: String?, val firstMediaPk: String?) {
    override fun toString(): String =
        "LabIds(firstCollectionId=${if (firstCollectionId == null) "none" else "<set>"}, " +
            "firstMediaPk=${if (firstMediaPk == null) "none" else "<set>"})"
}

/**
 * Sends ONE paced-by-the-caller request per [run] to an Instagram endpoint and reports what came back as a redacted
 * shape and a scrubbed copy, so the owner can see the real response structure without any real value leaving the
 * phone's memory. The raw body is read into a local, classified, parsed, and dropped: it is never stored, logged or
 * returned. There is no retry. The OkHttpClient is built on the first call that reaches the network.
 */
class AdapterLab(
    http: () -> OkHttpClient,
    private val cookies: CookieStore,
    private val base: HttpUrl = WebEndpoints.BASE,
) {
    private val http by lazy(http)

    /** One Scrubber for the whole lab session: the same real id gets the same synthetic id in every call. */
    val scrubber: Scrubber = Scrubber()

    /**
     * Sends exactly one request. [arg] is a collection id (SAVED_COLLECTION) or a media pk (MEDIA_INFO). An HTTP error
     * status is an answer, not an exception, even when its body can't be read (then it is classified from its headers);
     * a failure to connect, or to read the body of a 2xx or 5xx, is a [InstagramException.Transient].
     */
    suspend fun run(call: LabCall, arg: String?): LabResult {
        // Built first: a missing session or a bad id throws before the lazy client exists and before any request.
        val url = urlFor(call, arg)
        val raw = http.getRaw(url)
        // A 3xx or 4xx whose body was cut still says what it was in its headers (a rate limit must arm the cooldown).
        val error = if (raw.bodyUnreadable) {
            classifyUnreadable(raw.code, raw.location, raw.contentType)
        } else {
            ErrorClassifier.classify(raw.code, raw.location, raw.contentType, raw.body)
        }
        val json = if (raw.body.isEmpty()) null else runCatching { Json.parseToJsonElement(raw.body) }.getOrNull()
        val shape = when {
            raw.bodyUnreadable -> "(unreadable body)"
            raw.body.isEmpty() -> "(empty body)"
            json == null -> "(not JSON: ${mediaType(raw.contentType)}, ${raw.body.length} chars)"
            else -> ShapeDump.of(json)
        }
        val scrubbed = json?.let { PRETTY.encodeToString(JsonElement.serializer(), scrubber.scrub(it)) }
        return LabResult(
            call = call,
            httpCode = raw.code,
            classification = error?.let { it::class.simpleName ?: "Unknown" } ?: "ok",
            error = error,
            // Defence in depth: the rules keep these words out by construction, but a lab result is never worth the risk.
            shape = if (LabRules.hasForbiddenWord(shape)) "(withheld: shape matched the forbidden-word list)" else shape,
            scrubbedJson = scrubbed?.takeUnless(LabRules::hasForbiddenWord),
            ids = if (error == null && json != null) idsOf(call, json) else LabIds(null, null),
        )
    }

    private fun urlFor(call: LabCall, arg: String?): HttpUrl = when (call) {
        LabCall.CURRENT_USER ->
            WebEndpoints.currentUser(base, cookies.sessionUserId() ?: throw InstagramException.LoginRequired())
        LabCall.COLLECTIONS -> WebEndpoints.collections(base, null)
        LabCall.SAVED_ALL -> WebEndpoints.savedPosts(base, null)
        LabCall.SAVED_COLLECTION ->
            WebEndpoints.collectionPosts(base, arg ?: throw InstagramException.ShapeChanged("collection_id"), null)
        LabCall.MEDIA_INFO -> WebEndpoints.mediaInfo(base, arg ?: throw InstagramException.ShapeChanged("pk"))
    }

    /** The first MEDIA collection's id, and the first saved or info item's pk, read leniently: the shape may have drifted. */
    private fun idsOf(call: LabCall, json: JsonElement): LabIds {
        val items = (json as? JsonObject)?.get("items") as? JsonArray ?: return LabIds(null, null)
        val objects = items.filterIsInstance<JsonObject>()
        return when (call) {
            LabCall.COLLECTIONS -> LabIds(
                objects.firstOrNull { it.string("collection_type") == "MEDIA" }?.idString("collection_id")?.takeIf(::isId),
                null,
            )
            LabCall.SAVED_ALL, LabCall.SAVED_COLLECTION -> LabIds(
                null,
                objects.firstNotNullOfOrNull { (it["media"] as? JsonObject)?.idString("pk")?.takeIf(::isId) },
            )
            LabCall.MEDIA_INFO -> LabIds(null, objects.firstNotNullOfOrNull { it.idString("pk")?.takeIf(::isId) })
            LabCall.CURRENT_USER -> LabIds(null, null)
        }
    }

    /** An id is chained into a URL path later, so only plain digits count. */
    private fun isId(text: String): Boolean = text.isNotEmpty() && text.length <= 30 && text.all { it in '0'..'9' }

    /** The media type from a Content-Type header, or "unknown". Never anything but a short plain token. */
    private fun mediaType(contentType: String?): String =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { MEDIA_TYPE.matches(it) } ?: "unknown content type"

    private companion object {
        val PRETTY = Json { prettyPrint = true }
        val MEDIA_TYPE = Regex("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,40}")
    }
}
