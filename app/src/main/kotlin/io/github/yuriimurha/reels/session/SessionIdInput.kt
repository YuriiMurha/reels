package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.data.settings.REDACTED

/** Accepts a pasted sessionid in the shapes people copy it (Review Focus 4). Never makes a request. */
object SessionIdInput {
    /** [sessionId] is the credential: `toString()` never prints it. */
    data class Parsed(val sessionId: String, val userId: String) {
        override fun toString(): String = "Parsed(sessionId=$REDACTED, userId=$userId)"
    }

    fun parse(input: String): Parsed? {
        var value = input.trim().trim('"', '\'').trim()
        if (value.startsWith("sessionid=", ignoreCase = true)) value = value.substringAfter('=')
        value = value.substringBefore(';').trim().trim('"', '\'')
        // "42:ab" and "42%3aab" are the same id as "42%3Aab". Cookies carry the encoded form.
        val encoded = value.replace(":", "%3A").replace("%3a", "%3A")
        if (encoded.isEmpty() || !encoded.all(::isCookieOctet)) return null
        val userId = encoded.substringBefore(SEPARATOR, missingDelimiterValue = "")
        val secret = encoded.substringAfter(SEPARATOR, missingDelimiterValue = "")
        if (userId.isEmpty() || !userId.all { it in '0'..'9' } || secret.isEmpty()) return null
        return Parsed(sessionId = encoded, userId = userId)
    }

    private const val SEPARATOR = "%3A"

    /** RFC 6265 cookie-octet: printable ASCII without space, double quote, comma, semicolon and backslash. */
    private fun isCookieOctet(c: Char): Boolean = c.code.let {
        it == 0x21 || it in 0x23..0x2B || it in 0x2D..0x3A || it in 0x3C..0x5B || it in 0x5D..0x7E
    }
}
