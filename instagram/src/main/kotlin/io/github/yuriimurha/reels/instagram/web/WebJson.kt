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

/** GETs [url] and returns its JSON object, or throws the InstagramException the response signals. */
internal suspend fun OkHttpClient.getJsonObject(url: HttpUrl): JsonObject {
    val response = try {
        newCall(Request.Builder().url(url).get().build()).await()
    } catch (e: IOException) {
        throw InstagramException.Transient(e)
    }
    response.use {
        val body = try {
            it.body.string()
        } catch (e: IOException) {
            // The headers already said what happened. Only a 2xx or 5xx is worth a retry; a redirect or a 4xx
            // (challenge, login, rate limit) must still stop the run even though its body could not be read.
            if (it.code in 300..499) {
                throw ErrorClassifier.classify(it.code, it.header("Location"), it.header("Content-Type"), "")
                    ?: InstagramException.ShapeChanged("http.${it.code}")
            }
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
