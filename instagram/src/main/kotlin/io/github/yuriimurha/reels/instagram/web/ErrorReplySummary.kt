package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.lab.LabRules
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * The one-line, safe-to-paste summary of a non-2xx Instagram reply, for whichever transport received it: an allowlist of
 * fields, each value through the Adapter lab's own filter ([LabRules.isVisibleString]), and key names through
 * [LabRules.isSafeName].
 *
 * ```
 * <-- 401 reply: status=fail message="Please wait a few minutes before you try again." require_login=true keys=[message, require_login, status]
 * <-- 400 reply: non-JSON, text/html, 1523 bytes
 * <-- 429 reply: unreadable
 * ```
 *
 * A value that fails the filter is written as `<redacted len N>`; absent fields are left out; key names that fail the
 * filter are only counted (`+N other`). Never printed: the body, any URL (`checkpoint_url`, `challenge.url`), a cookie, an
 * id, a handle. A null [body] is `unreadable`. For a body that is not a JSON object the line gives its media type and its
 * size: [byteCount] when the caller knows the size of the bytes it decoded (default: the UTF-8 size of [body]), followed
 * by `+` when [cut] says the body was only peeked at.
 */
object ErrorReplySummary {
    fun of(code: Int, contentType: String?, body: String?, cut: Boolean = false, byteCount: Int? = null): String {
        val prefix = "<-- $code reply:"
        if (body == null) return "$prefix unreadable"
        val json = parseObject(body)
        if (json != null) return "$prefix ${summary(json)}"
        // Also what a JSON array or scalar gets: only an object has fields to show.
        val size = byteCount ?: body.toByteArray(Charsets.UTF_8).size
        return "$prefix non-JSON, ${contentTypeLabel(contentType?.toMediaTypeOrNull())}, $size${if (cut) "+" else ""} bytes"
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

    /** Every field the line can show. All of them are in [LabRules.VISIBLE_VALUE_KEYS]; a test pins that. */
    val STRING_FIELDS: List<String> = listOf("status", "message", "error_type")
    val BOOLEAN_FIELDS: List<String> = listOf("require_login", "spam", "lock")
    private val QUOTED_FIELDS: Set<String> = setOf("message", "error_type")
    private val SAFE_MEDIA_TYPE = Regex("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,40}")
}
