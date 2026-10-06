package io.github.yuriimurha.reels.session

/** Accepts a pasted sessionid in the shapes people copy it (Review Focus 4). Never makes a request. */
object SessionIdInput {
    data class Parsed(val sessionId: String, val userId: String)

    fun parse(input: String): Parsed? {
        var value = input.trim().trim('"', '\'').trim()
        if (value.startsWith("sessionid=", ignoreCase = true)) value = value.substringAfter('=')
        value = value.substringBefore(';').trim().trim('"', '\'')
        if (value.isEmpty() || value.any { it.isWhitespace() || it == ',' }) return null
        val encoded = value.replace(":", "%3A")
        if (!encoded.contains("%3A")) return null
        val userId = encoded.substringBefore("%3A")
        if (userId.isEmpty() || !userId.all { it.isDigit() }) return null
        return Parsed(sessionId = encoded, userId = userId)
    }
}
