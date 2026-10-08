package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a 3xx or 4xx whose body could not be read still tells, from its headers alone: a rate limit, a login bounce or a
 * challenge redirect keep their own classification. A plain 4xx (which with an empty body would be `ShapeChanged("http.400")`,
 * a not-found to some callers) becomes `ShapeChanged("http.<code>.unreadable")`, because the lost body may have held a
 * challenge or rate limit. [classifyReply] is its one caller, for every transport.
 */
internal fun classifyUnreadable(code: Int, location: String?, contentType: String?): InstagramException {
    val plain = "http.$code"
    val classified = ErrorClassifier.classify(code, location, contentType, "")
    return if (classified == null || (classified is InstagramException.ShapeChanged && classified.fieldPath == plain)) {
        InstagramException.ShapeChanged("$plain.unreadable")
    } else {
        classified
    }
}

internal fun parseObject(body: String): JsonObject? =
    runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/** An id that Instagram sends either as a JSON number or as a string; the exact digits, never a rounded double. */
internal fun JsonObject.idString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }
