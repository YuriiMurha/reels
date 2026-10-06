package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Maps one HTTP response to the failure it signals, or null when it is safe to parse (spec 6.4). */
object ErrorClassifier {
    private const val SITE = "https://www.instagram.com"

    fun classify(code: Int, location: String?, contentType: String?, body: String): InstagramException? {
        val json = parseObject(body)
        val markers = listOfNotNull(json?.string("message"), json?.string("error_type")).joinToString(" ").lowercase()
        when {
            "checkpoint_required" in markers || "challenge_required" in markers || json?.get("challenge") != null ->
                return InstagramException.ChallengeRequired(json?.challengeUrl()?.let(::absolute))
            "login_required" in markers || (json?.get("require_login") as? JsonPrimitive)?.booleanOrNull == true ->
                return InstagramException.LoginRequired()
            code == 429 || "feedback_required" in markers || "please wait a few minutes" in markers ->
                return InstagramException.RateLimited()
        }
        if (code in 300..399) {
            val target = location.orEmpty()
            return if ("/accounts/login" in target) {
                InstagramException.LoginRequired()
            } else {
                // A challenge, or an interstitial we don't know: stop and let the owner look in the WebView.
                InstagramException.ChallengeRequired(target.takeIf { it.isNotEmpty() }?.let(::absolute))
            }
        }
        return when {
            code == 401 || code == 403 -> InstagramException.LoginRequired()
            code >= 500 -> InstagramException.Transient()
            code >= 400 -> InstagramException.ShapeChanged("http.$code")
            json == null && contentType.orEmpty().contains("html") -> InstagramException.LoginRequired()
            json == null -> InstagramException.ShapeChanged("$")
            json.string("status") == "fail" -> InstagramException.ShapeChanged("status")
            else -> null
        }
    }

    private fun JsonObject.challengeUrl(): String? =
        (this["challenge"] as? JsonObject)?.string("url") ?: string("checkpoint_url")

    private fun absolute(url: String): String = if (url.startsWith("/")) SITE + url else url
}
