package io.github.yuriimurha.reels.instagram.lab

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.DocIdStore
import io.github.yuriimurha.reels.instagram.web.InstagramTransport
import io.github.yuriimurha.reels.instagram.web.RawReply
import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.instagram.web.WebParsers
import io.github.yuriimurha.reels.instagram.web.classifyReply
import io.github.yuriimurha.reels.instagram.web.idString
import io.github.yuriimurha.reels.instagram.web.sessionUserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

/**
 * The five calls the Adapter lab can make (spec 6.2): four endpoint GETs, and [COLLECTIONS], the website's own GraphQL query
 * for the collections and their names (spec 2026-10-09 §3.1).
 */
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
 * returned. There is no retry. The transport is built on the first call that reaches the network.
 *
 * [LabCall.COLLECTIONS] is one POST of the website's query with the doc id [docIds] holds; the lab never learns one. Its reply
 * is classified by the query's own rule (a stale doc id is [InstagramException.StaleQuery]) and shown after the website's
 * leading `for (;;);`.
 */
class AdapterLab(
    transport: () -> InstagramTransport,
    private val cookies: CookieStore,
    private val docIds: DocIdStore,
) {
    private val transport by lazy(transport)

    /** One Scrubber for the whole lab session: the same real id gets the same synthetic id in every call. */
    val scrubber: Scrubber = Scrubber()

    /**
     * Sends exactly one request. [arg] is a collection id (SAVED_COLLECTION) or a media pk (MEDIA_INFO). An HTTP error
     * status is an answer, not an exception, even when its body can't be read (then it is classified from its status); a
     * redirect is an answer too (it was not followed, so its target is unknown). A failure to connect, or to read the
     * body of a 2xx or 5xx, is a [InstagramException.Transient].
     */
    suspend fun run(call: LabCall, arg: String?): LabResult {
        val reply = send(call, arg)
        val graphQl = call == LabCall.COLLECTIONS
        // One rule for every transport. A 3xx or 4xx whose body was cut still says what it was (a rate limit must arm the cooldown).
        val error = if (graphQl) WebParsers.classifySavedCollections(reply) else classifyReply(reply)
        if (reply.body == null && error is InstagramException.Transient) throw error
        val body = if (graphQl) reply.body?.let(WebParsers::withoutGuard) else reply.body
        val json = if (body.isNullOrEmpty()) null else runCatching { Json.parseToJsonElement(body) }.getOrNull()
        val shape = when {
            reply.redirected -> "(redirect, not followed)"
            body == null -> "(unreadable body)"
            body.isEmpty() -> "(empty body)"
            json == null -> "(not JSON: ${mediaType(reply.contentType)}, ${body.length} chars)"
            else -> ShapeDump.of(json)
        }
        val scrubbed = json?.let { PRETTY.encodeToString(JsonElement.serializer(), scrubber.scrub(it)) }
        return LabResult(
            call = call,
            httpCode = reply.code,
            classification = error?.let { it::class.simpleName ?: "Unknown" } ?: "ok",
            error = error,
            // Defence in depth: the rules keep these words out by construction, but a lab result is never worth the risk.
            shape = if (LabRules.hasForbiddenWord(shape)) "(withheld: shape matched the forbidden-word list)" else shape,
            scrubbedJson = scrubbed?.takeUnless(LabRules::hasForbiddenWord),
            ids = if (error == null && json != null) idsOf(call, json) else LabIds(null, null),
        )
    }

    /**
     * The call's one request. Everything it needs is ready before the lazy transport is asked for: a missing session or a bad
     * id throws before the transport exists and before any request.
     */
    private suspend fun send(call: LabCall, arg: String?): RawReply = when (call) {
        LabCall.CURRENT_USER -> {
            // The URL carries no id, but without a session there is nothing to ask about: no request, as for the probe.
            cookies.sessionUserId() ?: throw InstagramException.LoginRequired()
            get(WebEndpoints.currentUser())
        }
        LabCall.COLLECTIONS -> {
            // The first page only, like every lab call.
            val docId = docIds.docId(WebGraphQl.SAVED_COLLECTIONS)
            transport.graphql(WebGraphQl.SAVED_COLLECTIONS, docId, WebGraphQl.savedCollectionsVariables(null))
        }
        LabCall.SAVED_ALL -> get(WebEndpoints.savedPosts(WebEndpoints.BASE, null))
        LabCall.SAVED_COLLECTION ->
            get(WebEndpoints.collectionPosts(WebEndpoints.BASE, arg ?: throw InstagramException.ShapeChanged("collection_id"), null))
        LabCall.MEDIA_INFO -> get(WebEndpoints.mediaInfo(WebEndpoints.BASE, arg ?: throw InstagramException.ShapeChanged("pk")))
    }

    /** [url] is built by the caller, so an invalid id throws before the lazy transport exists. */
    private suspend fun get(url: HttpUrl): RawReply = transport.get(WebEndpoints.relative(url))

    /**
     * The first user collection's id (fact AUTO: [WebParsers.isUserCollectionId]), and the first saved or info item's pk, read
     * leniently: the shape may have drifted.
     */
    private fun idsOf(call: LabCall, json: JsonElement): LabIds = when (call) {
        LabCall.COLLECTIONS -> LabIds(
            // Fact AUTO's user collection ids are digits only, so the id is also safe to chain into a path.
            objectsIn((json as? JsonObject)?.let(WebParsers::savedCollectionsRoot), "edges").firstNotNullOfOrNull { edge ->
                (edge["node"] as? JsonObject)?.idString("collection_id")?.takeIf(WebParsers::isUserCollectionId)
            },
            null,
        )
        LabCall.SAVED_ALL, LabCall.SAVED_COLLECTION -> LabIds(
            null,
            objectsIn(json as? JsonObject, "items").firstNotNullOfOrNull { (it["media"] as? JsonObject)?.idString("pk")?.takeIf(::isId) },
        )
        LabCall.MEDIA_INFO -> LabIds(
            null,
            objectsIn(json as? JsonObject, "items").firstNotNullOfOrNull { it.idString("pk")?.takeIf(::isId) },
        )
        LabCall.CURRENT_USER -> LabIds(null, null)
    }

    /** The objects of the array [key] of [parent]; none when either is missing or of another type. */
    private fun objectsIn(parent: JsonObject?, key: String): List<JsonObject> =
        (parent?.get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

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
