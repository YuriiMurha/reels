package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.SessionProbe

/**
 * Builds the real probe on the first request, so constructing [SessionRepository] never loads WebView (the probe's
 * transport is itself built by the first request that needs it).
 */
class LazySessionProbe(create: () -> SessionProbe) : SessionProbe {
    private val delegate by lazy(create)

    override suspend fun currentUser(): Account = delegate.currentUser()
}
