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

    private val SAFE_NAME = Regex("[a-z_][a-z0-9_]{0,47}")
    private val LONG_DIGITS = Regex("[0-9]{5,}")

    /**
     * True for a name that looks like schema, not data: 1 to 48 characters from `a-z`, `0-9` and `_`, starting with a
     * letter or `_`, with no run of 5 or more digits, not 16 or more characters long with a digit in it (hashes, tokens
     * and ids glued to a prefix), and with no word of the fixture guard list. Any uppercase letter, dot, dash or
     * non-ASCII character makes it data. This is a heuristic: a bare lowercase handle (`johndoe`, `jane_doe`) cannot
     * be told from a schema key such as `product_type` and is kept.
     */
    fun isSafeName(name: String): Boolean =
        SAFE_NAME.matches(name) && !LONG_DIGITS.containsMatchIn(name) &&
            !(name.length >= 16 && name.any { it in '0'..'9' }) && !hasForbiddenWord(name)

    /** How ShapeDump prints a key: itself when it is a schema name, else only its length and character class. */
    fun keyLabel(key: String): String = if (isSafeName(key)) key else "key(len ${key.length}, ${stringClass(key)})"

    private val ENUM_LIKE = Regex("[A-Za-z_]{1,40}")
    private val SENTENCE = Regex("[A-Za-z0-9 .,:;!?'()\\-_]{0,200}")

    /** Every mark of sentence punctuation (and the space): an id written as `31:00:00` or `(31)(00)` is still an id. */
    private val DIGIT_SEPARATORS = Regex("[ .,:;!?'\"()\\-_/]")
    private val DIGIT_RUN = Regex("[0-9]{3,}")
    private val WORD_SEPARATORS = Regex("[ ,;:!?'()\\-]+")

    /**
     * Whether a string under a visible-value key may be shown or kept: an enum-like value (1 to 40 letters and
     * underscores, `clips`, `challenge_required`, `ClipsMedia`), or a plain sentence. A plain sentence is at most 200
     * ASCII characters; no word has a `.`, `_` or `@` inside it (a handle, a host, an address); no run of 3 or more digits
     * even when written with sentence punctuation or spaces between them (`3,100`, `31:00:00`, `(31)(00)(00)`: an id);
     * no two capitalised words in a row (a name); and no word of the fixture guard list. Instagram's `message` is free
     * text, so it gets this check. One word of letters or underscores, in any case (`johndoe`, `JohnDoe`), passes as
     * enum-like: it can't be told from an enum value.
     */
    fun isVisibleString(text: String): Boolean {
        if (hasForbiddenWord(text)) return false
        if (ENUM_LIKE.matches(text)) return true
        if (!SENTENCE.matches(text)) return false
        if (DIGIT_RUN.containsMatchIn(text.replace(DIGIT_SEPARATORS, ""))) return false
        val words = text.split(WORD_SEPARATORS).map { it.trim('.') }.filter { it.isNotEmpty() }
        if (words.any { word -> word.any { it == '.' || it == '_' || it == '@' } }) return false
        return words.zipWithNext().none { (a, b) -> isCapitalised(a) && isCapitalised(b) }
    }

    private fun isCapitalised(word: String): Boolean = word.length >= 2 && word[0] in 'A'..'Z'

    /** Counts and sizes are short. A longer number under a visible key could be an id, so it is treated like any number. */
    fun isVisibleNumber(text: String): Boolean = text.length <= 6

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
