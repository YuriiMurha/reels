package io.github.yuriimurha.reels.instagram.lab

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The shape of a JSON response with every identifying value redacted (spec 6.3 Q1-Q9). One line per key, indented two
 * spaces per level. An array shows its length and expands only element `[0]`. A value is shown only under a key in
 * [LabRules.VISIBLE_VALUE_KEYS]; everything else shows its type and size, and a URL shows its host class and
 * parameter names, never the host or a value.
 */
object ShapeDump {
    fun of(element: JsonElement): String {
        val lines = ArrayList<String>()
        if (element is JsonObject) {
            // The root object has no key of its own: its keys start at the left margin.
            element.forEach { (key, value) -> describe(LabRules.keyLabel(key), key, value, 0, lines) }
        } else {
            describe("$", null, element, 0, lines)
        }
        return lines.joinToString("\n")
    }

    private fun describe(label: String, key: String?, value: JsonElement, depth: Int, lines: MutableList<String>) {
        val pad = "  ".repeat(depth)
        when (value) {
            is JsonObject -> {
                lines += "$pad$label object"
                value.forEach { (childKey, child) -> describe(LabRules.keyLabel(childKey), childKey, child, depth + 1, lines) }
            }
            is JsonArray -> {
                lines += "$pad$label array[${value.size}]"
                // Element 0 stands for the rest, and shares the array's key so id and visible-value rules still apply.
                value.firstOrNull()?.let { describe("[0]", key, it, depth + 1, lines) }
            }
            is JsonNull -> lines += "$pad$label null"
            is JsonPrimitive -> lines += "$pad$label ${primitive(key, value)}"
        }
    }

    private fun primitive(key: String?, value: JsonPrimitive): String {
        val text = value.content
        return when {
            value.isString -> when {
                key in LabRules.VISIBLE_VALUE_KEYS && LabRules.isVisibleString(text) -> "string = \"$text\""
                else -> LabRules.parseUrl(text)?.let(::urlShape) ?: "string(len ${text.length}, ${LabRules.stringClass(text)})"
            }
            text == "true" || text == "false" -> "boolean = $text"
            key in LabRules.VISIBLE_VALUE_KEYS && LabRules.isVisibleNumber(text) -> "number = $text"
            else -> "number(${text.count { it in '0'..'9' }} digits)"
        }
    }

    private fun urlShape(url: HttpUrl): String {
        val names = url.queryParameterNames.sorted().joinToString(", ") { if (LabRules.isSafeName(it)) it else "?" }
        return "url(host=${LabRules.hostClass(url.host)}, params=[$names], oe=${LabRules.oeState(url)})"
    }
}

/**
 * The rules ShapeDump and Scrubber share. Both have to agree on what may be shown, so they live in one place.
 * Everything here is a filter in the safe direction: when in doubt a value is redacted.
 */
internal object LabRules {
    /** Values under these keys are not identifying (counts, enums, sizes, flags, the error message), so they stay readable. */
    val VISIBLE_VALUE_KEYS: Set<String> = setOf(
        "media_type", "product_type", "collection_type", "status", "more_available", "num_results", "width", "height",
        "original_width", "original_height", "carousel_media_count", "error_type", "message", "spam", "require_login",
        "lock", "feedback_title", "has_more",
    )

    // Built from parts, like in FixtureGuardTest, so no literal session or cookie name sits in the source.
    private val FORBIDDEN_WORDS = listOf(
        "session" + "id", "csrf" + "token", "cookie" + ":", "ds_user" + "_id", "scontent", "fbcdn", "cdninstagram",
    )

    /** The word list of FixtureGuardTest: nothing a lab result shows or saves may contain one, whatever the case. */
    fun hasForbiddenWord(text: String): Boolean = FORBIDDEN_WORDS.any { text.contains(it, ignoreCase = true) }

    private val SAFE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,47}")
    private val LONG_DIGITS = Regex("[0-9]{5,}")

    /**
     * True for a plain identifier: a schema key such as `image_versions2` or a query parameter such as `_nc_ht`. A key
     * that is an id, a handle with dots, a token or a hash is data, not schema, and is redacted like a value.
     */
    fun isSafeName(name: String): Boolean =
        SAFE_NAME.matches(name) && !LONG_DIGITS.containsMatchIn(name) && !hasForbiddenWord(name)

    /** How ShapeDump prints a key: itself when it is a schema name, else only its length and character class. */
    fun keyLabel(key: String): String = if (isSafeName(key)) key else "key(len ${key.length}, ${stringClass(key)})"

    private val VISIBLE_TEXT = Regex("[A-Za-z0-9 _.,:;!?'()\\-]{0,200}")
    private val HOSTISH = Regex("[A-Za-z0-9-]+\\.(?:com|net|org|io|me|co|dev|app|invalid)\\b", RegexOption.IGNORE_CASE)

    /**
     * Whether a string under a visible-value key may be shown: short plain text with no URL, no host, no handle, no
     * long number (an id) and no forbidden word. Instagram's `message` is free text, so it gets this check.
     */
    fun isVisibleString(text: String): Boolean =
        VISIBLE_TEXT.matches(text) && !LONG_DIGITS.containsMatchIn(text) && !HOSTISH.containsMatchIn(text) &&
            !hasForbiddenWord(text)

    /** Counts and sizes are short. A long number under a visible key could be an id, so it is treated like any number. */
    fun isVisibleNumber(text: String): Boolean = text.length <= 9

    fun stringClass(text: String): String = when {
        text.isNotEmpty() && text.all { it in '0'..'9' } -> "digits"
        text.length >= 8 && text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } -> "hex"
        text.length >= 12 && text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' } -> "base64url"
        else -> "text"
    }

    /** An absolute http(s) URL, or null. Anything else (a relative path, bare text) is an ordinary string. */
    fun parseUrl(text: String): HttpUrl? =
        if (text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)) {
            text.toHttpUrlOrNull()
        } else {
            null
        }

    /** `instagram` for instagram.com and its subdomains, `cdn` for Instagram's and Facebook's CDN hosts, else `other`. */
    fun hostClass(host: String): String {
        val h = host.lowercase()
        return when {
            h == "instagram.com" || h.endsWith(".instagram.com") -> "instagram"
            "cdninstagram.com" in h || "fbcdn.net" in h -> "cdn"
            else -> "other"
        }
    }

    private val HEX = Regex("[0-9a-fA-F]{1,16}")

    fun isHex(text: String): Boolean = HEX.matches(text)

    /** The CDN expiry parameter: `hex` when it parses like MediaLinks reads it, `absent`, or `malformed`. */
    fun oeState(url: HttpUrl): String = when {
        "oe" !in url.queryParameterNames -> "absent"
        url.queryParameter("oe")?.let(::isHex) == true -> "hex"
        else -> "malformed"
    }

    /** Keys whose value is an id: `pk`, `id`, `fbid`, anything ending in `_id`, and id lists such as `saved_collection_ids`. */
    fun isIdKey(key: String?): Boolean =
        key != null && (key == "pk" || key == "id" || key == "fbid" || key.endsWith("_id") || key.endsWith("_ids"))
}
