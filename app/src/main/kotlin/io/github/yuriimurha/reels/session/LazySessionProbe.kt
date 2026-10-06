package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.SessionProbe

/**
 * Builds the real probe (and with it the OkHttp client, whose user agent needs the WebView provider) on the first
 * request, so constructing [SessionRepository] never loads WebView.
 */
class LazySessionProbe(create: () -> SessionProbe) : SessionProbe {
    private val delegate by lazy(create)

    override suspend fun currentUser(): Account = delegate.currentUser()
}
