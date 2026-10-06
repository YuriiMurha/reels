package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.cookieValue
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.Pacer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the login screen needs; split out so its ViewModel can be tested without Android. */
interface LoginSession {
    fun currentSessionId(): String?
    fun hasSessionCookies(): Boolean
    suspend fun validate(): SessionState
}

class SessionRepository(
    private val cookies: CookieStore,
    private val probe: SessionProbe,
    private val pacer: Pacer,
    private val settings: SettingsStore,
) : SessionSignals, LoginSession {
    /** The last known state, shown without a request (spec D7). */
    val state: Flow<SessionState> = settings.session.map { it.toState() }

    override fun currentSessionId(): String? = cookies.cookieValue(INSTAGRAM, "sessionid")

    override fun hasSessionCookies(): Boolean =
        currentSessionId() != null && cookies.cookieValue(INSTAGRAM, "ds_user_id") != null

    fun hasCsrfToken(): Boolean = cookies.cookieValue(INSTAGRAM, "csrftoken") != null

    /** One paced request on the interactive lane. Network, rate-limit and budget failures propagate unchanged. */
    override suspend fun validate(): SessionState {
        val handle = state.first().handle
        val result = try {
            SessionState.Valid(pacer.interactive { probe.currentUser() }.username)
        } catch (e: InstagramException.LoginRequired) {
            SessionState.Expired(handle)
        } catch (e: InstagramException.ChallengeRequired) {
            SessionState.Challenge(e.challengeUrl, handle)
        }
        settings.setSession(result.toStored())
        return result
    }

    /** Writes a pasted sessionid into the cookie store and validates it. Null (and no request) for anything else. */
    suspend fun pasteSessionId(input: String): SessionState? {
        val parsed = SessionIdInput.parse(input) ?: return null
        cookies.setCookie(INSTAGRAM, "sessionid=${parsed.sessionId}; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=31536000")
        cookies.setCookie(INSTAGRAM, "ds_user_id=${parsed.userId}; Domain=.instagram.com; Path=/; Secure; Max-Age=7776000")
        cookies.flush()
        return validate()
    }

    /** Forgets the session. The library is kept (spec 9.5). */
    suspend fun logout() {
        cookies.clearAll()
        settings.setSession(SessionState.LoggedOut.toStored())
    }

    override suspend fun loginRequired() {
        settings.setSession(SessionState.Expired(state.first().handle).toStored())
    }

    override suspend fun challengeRequired(challengeUrl: String?) {
        settings.setSession(SessionState.Challenge(challengeUrl, state.first().handle).toStored())
    }

    companion object {
        const val INSTAGRAM = "https://www.instagram.com"
    }
}
