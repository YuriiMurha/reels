package io.github.yuriimurha.reels.sync

/**
 * What a run (or the lab) tells the session layer about the session it worked under.
 *
 * Every signal carries the [epoch] that was current when the work began. The session layer bumps its epoch whenever the
 * jar's session is replaced or forgotten (logout, a paste, a paste's rollback, a new login its session check sees) and
 * ignores a signal from an older one: a request that was in flight across a logout must not expire, or revive, the login
 * that came after it.
 */
interface SessionSignals {
    /** Identifies the session a run starts with; a signal carrying an older epoch is ignored. */
    fun epoch(): Int

    /** A session check just succeeded as [username]. */
    suspend fun sessionOk(username: String, epoch: Int)

    suspend fun loginRequired(epoch: Int)

    suspend fun challengeRequired(challengeUrl: String?, epoch: Int)

    object None : SessionSignals {
        override fun epoch(): Int = 0

        override suspend fun sessionOk(username: String, epoch: Int) = Unit

        override suspend fun loginRequired(epoch: Int) = Unit

        override suspend fun challengeRequired(challengeUrl: String?, epoch: Int) = Unit
    }
}

/**
 * Whether a sync run may send its next request under the session it started with (R82). The engine asks before every
 * request, from inside the Pacer's gate, so a change that another lane, a logout or a paste made meanwhile is seen.
 */
enum class RunSession {
    /** The stored state is Valid and the jar still holds the session the run started under (the same epoch). */
    USABLE,

    /** The stored state is Challenge: the run stops as STOPPED_CHALLENGE. */
    CHALLENGE,

    /** Expired, logged out, or replaced since the run started (another epoch): the run stops as STOPPED_LOGIN. */
    NOT_USABLE,
}
