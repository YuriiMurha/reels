package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

/** Sends one GET. A connection-level IOException becomes [InstagramException.Transient]; an HTTP error status is a response. */
private suspend fun OkHttpClient.send(url: HttpUrl): Response =
    try {
        newCall(Request.Builder().url(url).get().build()).await()
    } catch (e: IOException) {
        throw InstagramException.Transient(e)
    }

/**
 * One HTTP response with the headers a classifier needs. [bodyUnreadable] is true when the headers arrived but reading
 * the body failed (a 3xx or 4xx only: see [getRaw]); [body] is then empty. [toString] omits the body: it is never logged.
 */
internal class RawResponse(
    val code: Int,
    val location: String?,
    val contentType: String?,
    val body: String,
    val bodyUnreadable: Boolean = false,
) {
    override fun toString(): String =
        if (bodyUnreadable) "RawResponse(code=$code, body=<unreadable>)" else "RawResponse(code=$code, body=<${body.length} chars>)"
}

/**
 * What a 3xx or 4xx whose body could not be read still tells, from its headers alone: a rate limit, a login bounce or a
 * challenge redirect keep their own classification. A plain 4xx (which with an empty body would be `ShapeChanged("http.400")`,
 * a not-found to some callers) becomes `ShapeChanged("http.<code>.unreadable")`, because the lost body may have held a
 * challenge or rate limit. One rule for [getJsonObject] and the Adapter lab.
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

/**
 * GETs [url] and returns the response as it is, whatever the status: classifying it is the caller's job (the Adapter
 * lab). A connect failure, and a body that can't be read on a 2xx or 5xx (worth a retry), throw
 * [InstagramException.Transient]. An unreadable body on a 3xx or 4xx is a response with [RawResponse.bodyUnreadable]:
 * its headers still say whether it was a rate limit, a login bounce or a challenge, and the caller must see that.
 */
internal suspend fun OkHttpClient.getRaw(url: HttpUrl): RawResponse =
    send(url).use {
        val location = it.header("Location")
        val contentType = it.header("Content-Type")
        try {
            RawResponse(it.code, location, contentType, it.body.string())
        } catch (e: IOException) {
            if (it.code !in 300..499) throw InstagramException.Transient(e)
            RawResponse(it.code, location, contentType, body = "", bodyUnreadable = true)
        }
    }

/** GETs [url] and returns its JSON object, or throws the InstagramException the response signals. */
internal suspend fun OkHttpClient.getJsonObject(url: HttpUrl): JsonObject {
    val response = send(url)
    response.use {
        val body = try {
            it.body.string()
        } catch (e: IOException) {
            // The headers already said what happened. Only a 2xx or 5xx is worth a retry; a redirect or a 4xx
            // (challenge, login, rate limit) must still stop the run even though its body could not be read.
            if (it.code in 300..499) throw classifyUnreadable(it.code, it.header("Location"), it.header("Content-Type"))
            throw InstagramException.Transient(e)
        }
        ErrorClassifier.classify(it.code, it.header("Location"), it.header("Content-Type"), body)?.let { error -> throw error }
        return parseObject(body) ?: throw InstagramException.ShapeChanged("$")
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
