package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Maps one HTTP response to the failure it signals, or null when it is safe to parse (spec 6.4). */
object ErrorClassifier {
    fun classify(code: Int, location: String?, contentType: String?, body: String): InstagramException? {
        val json = parseObject(body)
        val markers = listOfNotNull(json?.string("message"), json?.string("error_type")).joinToString(" ").lowercase()
        // Precedence: a challenge needs the owner, a rate limit arms the cooldown, and only then is it a plain logout
        // (Instagram sends "please wait" together with require_login, and the cooldown must not be lost to it).
        when {
            "checkpoint_required" in markers || "challenge_required" in markers || json?.get("challenge") is JsonObject ->
                return InstagramException.ChallengeRequired(json?.challengeUrl()?.let(::safeChallengeUrl))
            code == 429 || "feedback_required" in markers || "please wait a few minutes" in markers ->
                return InstagramException.RateLimited()
            "login_required" in markers || (json?.get("require_login") as? JsonPrimitive)?.booleanOrNull == true ->
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
