package io.github.yuriimurha.reels.sync

/** Session problems the engine discovers mid-run. Wired to SessionRepository in Task 17. */
interface SessionSignals {
    suspend fun loginRequired()

    suspend fun challengeRequired(challengeUrl: String?)

    object None : SessionSignals {
        override suspend fun loginRequired() = Unit

        override suspend fun challengeRequired(challengeUrl: String?) = Unit
    }
}
