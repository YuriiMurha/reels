package io.github.yuriimurha.reels.instagram.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl

/**
 * Rewrites a response into a synthetic copy that can become a test fixture: same structure and schema keys, no real
 * value. Ids map to ids of the same digit count, handles to `user_<n>`, captions to `caption <n>`, shortcodes to
 * `C<n>` of the same length, CDN links to `https://cdn.example.invalid/m/<n>` with the original parameter names, other
 * strings to `s_<n>`, other numbers to `n`, timestamps to `1700000000 + n * 86400`. Booleans, null and the values under
 * [LabRules.VISIBLE_VALUE_KEYS] stay.
 *
 * One Scrubber keeps its mappings for its whole life, so the same real id becomes the same synthetic id in every
 * response of a lab session and the saved fixtures cross-reference each other. The mappings live in memory only.
 */
class Scrubber {
    private class Interner {
        private val seen = LinkedHashMap<String, Int>()

        /** The 1-based index of [value] among the distinct values seen, stable for the Interner's life. */
        fun of(value: String): Int = seen.getOrPut(value) { seen.size + 1 }
    }

    private val ids = HashMap<String, String>()
    private val nextIdByLength = HashMap<Int, Int>()
    private val users = Interner()
    private val captions = Interner()
    private val codes = Interner()
    private val urls = Interner()
    private val strings = Interner()
    private val numbers = Interner()
    private val times = Interner()
    private val keys = Interner()

    /** The scrubbed copy of [element]. Not a view: the original is left alone. */
    @Synchronized
    fun scrub(element: JsonElement): JsonElement = scrub(null, element)

    private fun scrub(key: String?, element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            LinkedHashMap<String, JsonElement>().also { out ->
                element.forEach { (childKey, child) -> out[scrubKey(childKey)] = scrub(childKey, child) }
            },
        )
        // Array elements keep the array's key, so `saved_collection_ids` elements are ids and a visible key stays visible.
        is JsonArray -> JsonArray(element.map { scrub(key, it) })
        is JsonNull -> element
        is JsonPrimitive -> when {
            element.isString -> scrubString(key, element.content)
            element.content == "true" || element.content == "false" -> element
            else -> scrubNumber(key, element.content)
        }
    }

    /** A schema key stays; a key that is itself data (an id, a handle, a token) is mapped like a value. */
    private fun scrubKey(key: String): String = when {
        LabRules.isSafeName(key) -> key
        isDigits(key) || isDigitPair(key) -> mapId(key)
        else -> "key_${keys.of(key)}"
    }

    private fun scrubString(key: String?, value: String): JsonElement {
        if (key in LabRules.VISIBLE_VALUE_KEYS && LabRules.isVisibleString(value)) return JsonPrimitive(value)
        // Any all-digit string is an id, whatever its key; "<digits>_<digits>" is a media id with its owner's id.
        if (isDigits(value) || isDigitPair(value)) return JsonPrimitive(mapId(value))
        when (key) {
            "username", "full_name" -> return JsonPrimitive("user_${users.of(value)}")
            "text" -> return JsonPrimitive("caption ${captions.of(value)}")
            "code" -> return JsonPrimitive("C" + codes.of(value).toString().padStart(maxOf(value.length - 1, 0), '0'))
        }
        LabRules.parseUrl(value)?.let { return JsonPrimitive(scrubUrl(it, value)) }
        return JsonPrimitive("s_${strings.of(value)}")
    }

    private fun scrubNumber(key: String?, text: String): JsonElement = when {
        key in LabRules.VISIBLE_VALUE_KEYS && LabRules.isVisibleNumber(text) -> number(text)
        LabRules.isIdKey(key) && isDigits(text) -> number(mapId(text))
        key != null && (key.endsWith("_at") || key.endsWith("timestamp")) ->
            JsonPrimitive(1_700_000_000L + times.of(text) * 86_400L)
        else -> JsonPrimitive(numbers.of(text))
    }

    /** A JSON number token written exactly as [digits] says, so a 25-digit id never goes through a Long. */
    private fun number(digits: String): JsonElement =
        runCatching { Json.parseToJsonElement(digits) }.getOrElse { JsonPrimitive(numbers.of(digits)) }

    private fun scrubUrl(url: HttpUrl, original: String): String {
        val builder = HttpUrl.Builder().scheme("https").host(SYNTHETIC_HOST).addPathSegments("m/${urls.of(original)}")
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i).takeIf(LabRules::isSafeName) ?: "p$i"
            val value = url.queryParameterValue(i)
            // oe is the CDN link expiry (a hex timestamp): the one parameter value the fixtures need to keep.
            builder.addQueryParameter(name, if (name == "oe" && value != null && LabRules.isHex(value)) value else "x")
        }
        return builder.build().toString()
    }

    private fun isDigits(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }

    private fun isDigitPair(text: String): Boolean {
        val parts = text.split('_')
        return parts.size == 2 && parts.all(::isDigits)
    }

    /** The synthetic id for [original]: the same digit count, the same answer every time, never the original itself. */
    private fun mapId(original: String): String {
        if ('_' in original) return original.split('_').joinToString("_", transform = ::mapDigits)
        return mapDigits(original)
    }

    private fun mapDigits(original: String): String = ids.getOrPut(original) {
        val length = original.length
        var candidate: String
        do {
            val n = (nextIdByLength[length] ?: 0) + 1
            nextIdByLength[length] = n
            candidate = if (length == 1) (n % 10).toString() else "1" + n.toString().takeLast(length - 1).padStart(length - 1, '0')
        } while (candidate == original)
        candidate
    }

    private companion object {
        const val SYNTHETIC_HOST = "cdn.example.invalid"
    }
}
