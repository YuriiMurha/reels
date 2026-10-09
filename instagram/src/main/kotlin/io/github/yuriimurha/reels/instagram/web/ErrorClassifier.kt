package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Maps one HTTP response to the failure it signals, or null when it is safe to parse (spec 6.4). */
object ErrorClassifier {
    /** Text that names a challenge, a rate limit or a logout, in a reply's message fields (lower case). */
    private val CHALLENGE_MARKERS = listOf("checkpoint_required", "challenge_required")
    private val RATE_LIMIT_MARKERS = listOf("feedback_required", "please wait a few minutes")
    private val LOGIN_MARKERS = listOf("login_required")

    private fun String.hasAny(markers: List<String>): Boolean = markers.any { it in this }

    fun classify(code: Int, location: String?, contentType: String?, body: String): InstagramException? {
        val json = parseObject(body)
        val markers = listOfNotNull(json?.string("message"), json?.string("error_type")).joinToString(" ").lowercase()
        // Precedence: a challenge needs the owner, a rate limit arms the cooldown, and only then is it a plain logout
        // (Instagram sends "please wait" together with require_login, and the cooldown must not be lost to it).
        when {
            markers.hasAny(CHALLENGE_MARKERS) || json?.get("challenge") is JsonObject ->
                return InstagramException.ChallengeRequired(json?.challengeUrl()?.let(::safeChallengeUrl))
            code == 429 || markers.hasAny(RATE_LIMIT_MARKERS) ->
                return InstagramException.RateLimited()
            markers.hasAny(LOGIN_MARKERS) || (json?.get("require_login") as? JsonPrimitive)?.booleanOrNull == true ->
                return InstagramException.LoginRequired()
        }
        if (code in 300..399) {
            val target = location.orEmpty()
            return if ("/accounts/login" in target) {
                InstagramException.LoginRequired()
            } else {
                // A challenge, or an interstitial we don't know: stop and let the owner look in the WebView.
                InstagramException.ChallengeRequired(target.takeIf { it.isNotEmpty() }?.let(::safeChallengeUrl))
            }
        }
        return when {
            code == 401 || code == 403 -> InstagramException.LoginRequired()
            code >= 500 -> InstagramException.Transient()
            code >= 400 -> InstagramException.ShapeChanged("http.$code")
            json == null && contentType.orEmpty().contains("html", ignoreCase = true) -> InstagramException.LoginRequired()
            json == null -> InstagramException.ShapeChanged("$")
            json.string("status") == "fail" -> InstagramException.ShapeChanged("status")
            else -> null
        }
    }

    /**
     * The failure the markers of [classify] name in [texts] alone, with [classify]'s precedence (a challenge, then a rate limit,
     * then a logout), or null when none does. For error text a reply carries inside its body, such as a GraphQL `errors` entry
     * (R12). A challenge found this way has no URL: there is no field to take a safe one from.
     */
    internal fun markedFailure(texts: List<String>): InstagramException? {
        val markers = texts.joinToString(" ").lowercase()
        return when {
            markers.hasAny(CHALLENGE_MARKERS) -> InstagramException.ChallengeRequired(null)
            markers.hasAny(RATE_LIMIT_MARKERS) -> InstagramException.RateLimited()
            markers.hasAny(LOGIN_MARKERS) -> InstagramException.LoginRequired()
            else -> null
        }
    }

    private fun JsonObject.challengeUrl(): String? =
        (this["challenge"] as? JsonObject)?.string("url") ?: string("checkpoint_url")

    /**
     * Resolves [raw] against the site and keeps it only when it is https on instagram.com or a subdomain: the URL ends
     * up in a WebView, so a foreign or plain-http one is dropped (the challenge itself still stops the run).
     */
    private fun safeChallengeUrl(raw: String): String? {
        val url = WebEndpoints.BASE.resolve(raw) ?: return null
        val host = url.host
        val onInstagram = host == "instagram.com" || host.endsWith(".instagram.com")
        return url.toString().takeIf { url.scheme == "https" && onInstagram }
    }
}
