package io.github.yuriimurha.reels.instagram

/** Every failure the adapter reports. Messages never contain session material or challenge URLs. */
sealed class InstagramException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class LoginRequired : InstagramException("Instagram session is not logged in")

    /** [challengeUrl] is where Instagram wants the owner to verify. Never log it. */
    class ChallengeRequired(val challengeUrl: String?) : InstagramException("Instagram requires verification")

    class RateLimited : InstagramException("Instagram is limiting requests")

    class Transient(cause: Throwable? = null) : InstagramException("Temporary network or server problem", cause)

    class ShapeChanged(val fieldPath: String) : InstagramException("Unexpected Instagram response at $fieldPath")

    /**
     * The website no longer runs the doc id the app sent for the GraphQL query [query] (its friendly name): the current id has
     * to be learned from the site again (spec 2026-10-09 §3.3). Never carries a doc id. [detail] says what kind of reply it was
     * when that was no GraphQL reply (R22: `not graphql`, `http 404`): null for a GraphQL one, the plain "the id is gone".
     */
    class StaleQuery(val query: String, val detail: String? = null) : InstagramException("stale query $query")

    /**
     * R20: a GraphQL query the transport could not send at all (the page had no tokens to send it with): nothing went out, so it
     * says nothing about the doc id or the session. Never retried (it is not [Transient]): the sync falls back to the last names
     * at once. The reason is a fixed text of the app's own, never a value from a page.
     */
    class QueryNotSent(reason: String) : InstagramException("query not sent: $reason")

    /**
     * The collections repair could not run or did not find the query; sync falls back to the last names. The reason is a fixed
     * text of the app's own, never a value from a page or a reply.
     */
    sealed class RepairUnavailable(reason: String) : InstagramException("collections repair unavailable: $reason")

    /** The repair was not attempted: the 24 h limit, or no stored handle. */
    class RepairSkipped(reason: String) : RepairUnavailable(reason)

    /** The repair ran and failed: no query seen, a page error, or a stale repaired reply. */
    class RepairFailed(reason: String) : RepairUnavailable(reason)
}
