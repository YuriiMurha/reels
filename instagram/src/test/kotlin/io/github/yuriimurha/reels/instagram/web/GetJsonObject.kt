package io.github.yuriimurha.reels.instagram.web

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * The JSON object a GET of [url] answers, or the InstagramException it signals: the whole path production takes
 * ([OkHttpTransport], then [classifyReply]), against a MockWebServer URL. [url]'s own origin is the transport's base.
 */
internal suspend fun OkHttpClient.getJsonObject(url: HttpUrl): JsonObject =
    OkHttpTransport(this, url.resolve("/")!!).get(WebEndpoints.relative(url)).jsonOrThrow()
