package io.github.yuriimurha.reels.instagram.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Rewrites a response into a synthetic copy that can become a test fixture in a public repo: same structure and schema
 * keys, no real value. Ids map to ids of the same digit count, handles to `user_<n>`, captions to `caption <n>`,
 * shortcodes to `C<n>` of the same length, CDN links to `https://cdn.example.invalid/m/<n>` with the original parameter
 * names, other strings to `s_<n>`, other numbers to `n`, timestamps to `1700000000 + n * 86400`. Booleans and null
 * stay. A value under a [LabRules.VISIBLE_VALUE_KEYS] key stays only if it is enum-like or a plain sentence (see
 * [LabRules.isVisibleString]) or a number of at most 6 characters; otherwise it becomes `text <n>` or `n`. A key or URL
 * parameter name that is data (see [LabRules.isSafeName]) is replaced too.
 *
 * It is a heuristic, not a proof: a lowercase handle used as a key, or a one-word value of letters and underscores (any
 * case) under an enum-like key, cannot be told from schema, so read a scrubbed file before committing it.
 *
 * One Scrubber keeps its mappings for its whole life, so the same real id becomes the same synthetic id in every
 * response of a lab session and the saved fixtures cross-reference each other. The mappings live in memory only and are
 * keyed by a salted SHA-256 digest of each raw value, so the Scrubber never holds a raw id, handle, caption or URL.
 */
class Scrubber {
    private val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun digest(value: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(value.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private inner class Interner {
        private val seen = LinkedHashMap<String, Int>()

        /** The 1-based index of [value] among the distinct values seen, stable for the Interner's life. */
        fun of(value: String): Int = seen.getOrPut(digest(value)) { seen.size + 1 }
    }

    private val ids = HashMap<String, String>()
    private val nextIdByLength = HashMap<Int, Int>()
    private val users = Interner()
    private val captions = Interner()
    private val codes = Interner()
    private val urls = Interner()
    private val strings = Interner()
    private val texts = Interner()
    private val numbers = Interner()
    private val times = Interner()
    private val keys = Interner()

    /** The scrubbed copy of [element]. Not a view: the original is left alone. */
    @Synchronized
    fun scrub(element: JsonElement): JsonElement = scrub(null, element)

    private fun scrub(key: String?, element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            // Real schema keys stay as they are, so a synthetic name must never equal one of them.
            val kept = element.keys.filterTo(HashSet()) { LabRules.isSafeName(it) }
            JsonObject(
                LinkedHashMap<String, JsonElement>().also { out ->
                    element.forEach { (childKey, child) -> out[scrubKey(childKey, kept)] = scrub(childKey, child) }
                },
            )
        }
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
    private fun scrubKey(key: String, kept: Set<String>): String = when {
        LabRules.isSafeName(key) -> key
        isDigits(key) || isDigitPair(key) -> mapId(key)
        else -> freeName("key", keys.of(key), kept)
    }

    /** `<prefix>_<n>`, or the first `<prefix>_<n>x<i>` that is not one of [taken]: a synthetic name never overwrites a real key. */
    private fun freeName(prefix: String, n: Int, taken: Set<String>): String {
        var name = "${prefix}_$n"
        var i = 0
        while (name in taken) name = "${prefix}_${n}x${++i}"
        return name
    }

    private fun scrubString(key: String?, value: String): JsonElement {
        // The output may go into a public repo, so a visible key keeps only what is clearly not a name, id or link.
        if (key in LabRules.VISIBLE_VALUE_KEYS) {
            return JsonPrimitive(if (LabRules.isVisibleString(value)) value else "text ${texts.of(value)}")
        }
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
        key in LabRules.VISIBLE_VALUE_KEYS ->
            if (LabRules.isVisibleNumber(text)) number(text) else JsonPrimitive(numbers.of(text))
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
            val name = url.queryParameterName(i).takeIf(LabRules::isSafeName) ?: "p_$i"
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

    private fun mapId(original: String): String {
        if ('_' in original) return original.split('_').joinToString("_", transform = ::mapDigits)
        return mapDigits(original)
    }

    private fun mapDigits(original: String): String = ids.getOrPut(digest(original)) { freshId(original) }

    /**
     * A stand-in for [original]: the same digit count, never the original and never one already handed out. Each digit
     * count has its own counter and walks up through its numbers (for 2 digits: 11, 12, ... 99). When a length is used
     * up, the stand-in gets one more digit rather than repeat one: two different ids must never share a stand-in.
     */
    private fun freshId(original: String): String {
        var length = original.length
        while (true) {
            while (true) {
                val k = (nextIdByLength[length] ?: 0) + 1
                val candidate = candidateId(length, k) ?: break
                nextIdByLength[length] = k
                if (candidate != original) return candidate
            }
            length++
        }
    }

    /** The [k]th stand-in of [length] digits (1-based), or null when that length has no more. */
    private fun candidateId(length: Int, k: Int): String? =
        if (length == 1) {
            if (k <= 10) (k % 10).toString() else null
        } else {
            BigInteger.TEN.pow(length - 1).add(BigInteger.valueOf(k.toLong())).toString().takeIf { it.length == length }
        }

    private companion object {
        const val SYNTHETIC_HOST = "cdn.example.invalid"
    }
}
