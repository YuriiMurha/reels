package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.lab.LabRules
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Debug builds only (`HttpClientFactory.create` adds it when a logger is passed). For every response that is not 2xx it
 * logs ONE line that says what Instagram answered, in a form that is safe to paste: an allowlist of fields, each value
 * through the Adapter lab's own filter ([LabRules.isVisibleString]), and key names through [LabRules.isSafeName].
 *
 * ```
 * <-- 401 reply: status=fail message="Please wait a few minutes before you try again." require_login=true keys=[message, require_login, status]
 * <-- 400 reply: non-JSON, text/html, 1523 bytes
 * ```
 *
 * A value that fails the filter is written as `<redacted len N>`; absent fields are left out; key names that fail the
 * filter are only counted (`+N other`). Never printed: the body, any URL (`checkpoint_url`, `challenge.url`), a cookie, an
 * id, a handle. A 2xx is never touched.
 *
 * It peeks at most [PEEK_LIMIT] bytes ([Response.peekBody]), so the body the caller reads is whole and the log costs
 * nothing on the wire. A network interceptor sees the body as it came off the wire, still gzipped when OkHttp asked for
 * gzip by itself (it does, and un-zips only after the network interceptors), so the peek is un-zipped here. Whatever goes
 * wrong while looking (a body cut off mid-read, a stream that is not gzip) becomes `reply: unreadable`: this logger never
 * changes what the caller gets.
 *
 * Registered BEFORE the HttpLoggingInterceptor, so its line comes after that response's header block.
 */
internal class ErrorReplyLogger(private val log: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code !in 200..299) log(describe(response))
        return response
    }

    private fun describe(response: Response): String {
        val prefix = "<-- ${response.code} reply:"
        return try {
            val peeked = response.peekBody(PEEK_LIMIT)
            val wire = peeked.bytes()
            val cut = wire.size >= PEEK_LIMIT
            val gzipped = response.header("Content-Encoding")?.trim().equals("gzip", ignoreCase = true)
            val bytes = if (gzipped) gunzip(wire) else wire
            val json = parseObject(bytes.toString(Charsets.UTF_8))
            if (json != null) {
                "$prefix ${summary(json)}"
            } else {
                // Also what a JSON array or scalar gets: only an object has fields to show.
                "$prefix non-JSON, ${contentTypeLabel(peeked.contentType())}, ${bytes.size}${if (cut) "+" else ""} bytes"
            }
        } catch (e: Exception) {
            "$prefix unreadable"
        }
    }

    private fun summary(json: JsonObject): String {
        val parts = ArrayList<String>()
        for (key in STRING_FIELDS) {
            val value = json[key] ?: continue
            parts += "$key=${stringField(value, quoted = key in QUOTED_FIELDS)}"
        }
        for (key in BOOLEAN_FIELDS) {
            val value = json[key] ?: continue
            parts += "$key=${booleanField(value)}"
        }
        val safe = json.keys.filter(LabRules::isSafeName).sorted()
        val others = json.size - safe.size
        parts += "keys=[${(safe + listOfNotNull(if (others > 0) "+$others other" else null)).joinToString(", ")}]"
        return parts.joinToString(" ")
    }

    private fun stringField(value: JsonElement, quoted: Boolean): String = when {
        value is JsonNull -> "null"
        value is JsonPrimitive && value.isString -> {
            val text = value.content
            when {
                !LabRules.isVisibleString(text) -> "<redacted len ${text.length}>"
                quoted -> "\"$text\""
                else -> text
            }
        }
        else -> "<other type>"
    }

    private fun booleanField(value: JsonElement): String = when {
        value is JsonNull -> "null"
        value is JsonPrimitive && !value.isString -> value.booleanOrNull?.toString() ?: "<other type>"
        else -> "<other type>"
    }

    private fun contentTypeLabel(type: MediaType?): String {
        val label = type?.let { "${it.type}/${it.subtype}".lowercase() } ?: return "no content-type"
        return if (SAFE_MEDIA_TYPE.matches(label)) label else "other content-type"
    }

    /** Un-zips what it can: a stream cut off at the peek limit keeps its decoded start; one that is not gzip yields nothing. */
    private fun gunzip(wire: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            GZIPInputStream(ByteArrayInputStream(wire)).use { input ->
                val buffer = ByteArray(4096)
                while (out.size() < UNZIPPED_LIMIT) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
        } catch (_: IOException) {
            // Keep what was decoded so far.
        }
        return out.toByteArray()
    }

    companion object {
        const val PEEK_LIMIT: Long = 16L * 1024
        private const val UNZIPPED_LIMIT = 64 * 1024

        /** Every field the line can show. All of them are in [LabRules.VISIBLE_VALUE_KEYS]; a test pins that. */
        val STRING_FIELDS: List<String> = listOf("status", "message", "error_type")
        val BOOLEAN_FIELDS: List<String> = listOf("require_login", "spam", "lock")
        private val QUOTED_FIELDS: Set<String> = setOf("message", "error_type")
        private val SAFE_MEDIA_TYPE = Regex("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,40}")
    }
}
