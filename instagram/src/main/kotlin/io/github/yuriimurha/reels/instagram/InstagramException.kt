package io.github.yuriimurha.reels.instagram

/** Every failure the adapter reports. Messages never contain session material or challenge URLs. */
sealed class InstagramException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class LoginRequired : InstagramException("Instagram session is not logged in")

    /** [challengeUrl] is where Instagram wants the owner to verify. Never log it. */
    class ChallengeRequired(val challengeUrl: String?) : InstagramException("Instagram requires verification")

    class RateLimited : InstagramException("Instagram is limiting requests")

    class Transient(cause: Throwable? = null) : InstagramException("Temporary network or server problem", cause)

    class ShapeChanged(val fieldPath: String) : InstagramException("Unexpected Instagram response at $fieldPath")
}
