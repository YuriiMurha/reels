package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.WebSessionCookies
import io.github.yuriimurha.reels.instagram.web.cookieValue
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.Pacer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** What the login screen needs; split out so its ViewModel can be tested without Android. */
interface LoginSession {
    /** A short, non-reversible tag of the current sessionid (never the id itself), or null. Equal tags mean the same session. */
    fun currentSessionFingerprint(): String?
    fun hasSessionCookies(): Boolean

    /** Whether the jar already holds a csrftoken. A local read, never a request. */
    fun hasCsrfToken(): Boolean

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

    /**
     * Serialises everything that changes the cookie jar or the stored state. [validate] does not hold it while its
     * request is in flight (logout must not wait for the network); it checks [sessionEpoch] afterwards instead.
     */
    private val lock = Mutex()

    /**
     * Bumped, under [lock], whenever the jar's session is replaced or forgotten: logout, a paste and a paste's rollback.
     * Volatile so [epoch] can be read without the lock, at the start of a run, even while a paste holds it.
     */
    @Volatile
    private var sessionEpoch = 0

    override fun epoch(): Int = sessionEpoch

    override fun currentSessionFingerprint(): String? = sessionId()?.let(::fingerprintOf)

    private fun sessionId(): String? = cookies.cookieValue(INSTAGRAM, "sessionid")

    private fun userId(): String? = cookies.cookieValue(INSTAGRAM, "ds_user_id")

    override fun hasSessionCookies(): Boolean = sessionId() != null && userId() != null

    override fun hasCsrfToken(): Boolean = cookies.cookieValue(INSTAGRAM, "csrftoken") != null

    /**
     * One paced request on the interactive lane. Network, rate-limit and budget failures propagate unchanged.
     * Without session cookies there is nothing to check: the state becomes LoggedOut and no request is made.
     * A result that arrives after a logout or a paste replaced the session is discarded.
     */
    override suspend fun validate(): SessionState {
        val (epoch, handle) = lock.withLock {
            if (!hasSessionCookies()) return store(SessionState.LoggedOut)
            sessionEpoch to state.first().handle
        }
        val result = probeSession(handle)
        return lock.withLock {
            if (epoch != sessionEpoch) {
                state.first()
            } else {
                // Valid is persisted at once, but Chromium commits cookies lazily: flush so a kill right after a
                // WebView login cannot leave "Logged in as" with no sessionid behind it.
                if (result is SessionState.Valid) cookies.flush()
                store(result)
            }
        }
    }

    /**
     * Writes a pasted sessionid into the cookie store and validates it. Null (and no request) for anything else.
     * Transactional: only a [SessionState.Valid] result commits. A refusal by the Pacer comes before any cookie is
     * written; if the check fails, is cancelled, or Instagram rejects the id (Expired, Challenge) the previous cookies
     * are put back and the stored state, which is only written on commit, stays as it was. A rejection is still
     * returned so the caller can say why.
     */
    suspend fun pasteSessionId(input: String): SessionState? {
        val parsed = SessionIdInput.parse(input) ?: return null
        return lock.withLock {
            pacer.ensureAllowed()
            val previousSession = sessionId()
            val previousUser = userId()
            sessionEpoch++
            writeSessionCookies(WebSessionCookies.sessionCookie(parsed.sessionId), WebSessionCookies.userCookie(parsed.userId))
            suspend fun rollBack() = withContext(NonCancellable) {
                writeSessionCookies(WebSessionCookies.sessionCookie(previousSession), WebSessionCookies.userCookie(previousUser))
                sessionEpoch++
            }
            val result = try {
                probeSession(state.first().handle).also { if (it is SessionState.Valid) store(it) }
            } catch (e: Throwable) {
                rollBack()
                throw e
            }
            if (result !is SessionState.Valid) rollBack()
            result
        }
    }

    /** Forgets the session. The library is kept (spec 9.5). */
    suspend fun logout() {
        lock.withLock {
            sessionEpoch++
            cookies.clearAll()
            settings.setSession(SessionState.LoggedOut.toStored())
        }
    }

    /**
     * A session check made by a run succeeded. Restores `Valid` after a banner an earlier run (or the lab) left behind. The
     * stored state is only touched when it would change, so a run that finishes under an already-valid session does no
     * write. Cookies are flushed with it, as for any `Valid` result (R45). Ignored for a stale [epoch] and when the jar holds
     * no session, so a run that began before a logout can never log the owner back in.
     */
    override suspend fun sessionOk(username: String, epoch: Int) {
        lock.withLock {
            if (epoch != sessionEpoch || !hasSessionCookies()) return
            if (state.first() == SessionState.Valid(username)) return
            cookies.flush()
            store(SessionState.Valid(username))
        }
    }

    override suspend fun loginRequired(epoch: Int) {
        lock.withLock {
            if (epoch != sessionEpoch) return
            store(if (hasSessionCookies()) SessionState.Expired(state.first().handle) else SessionState.LoggedOut)
        }
    }

    override suspend fun challengeRequired(challengeUrl: String?, epoch: Int) {
        lock.withLock {
            if (epoch != sessionEpoch) return
            store(SessionState.Challenge(challengeUrl, state.first().handle))
        }
    }

    private suspend fun probeSession(handle: String?): SessionState = try {
        SessionState.Valid(pacer.interactive { probe.currentUser() }.username)
    } catch (e: InstagramException.LoginRequired) {
        SessionState.Expired(handle)
    } catch (e: InstagramException.ChallengeRequired) {
        SessionState.Challenge(e.challengeUrl, handle)
    }

    private suspend fun store(result: SessionState): SessionState {
        settings.setSession(result.toStored())
        return result
    }

    private fun writeSessionCookies(sessionCookie: String, userCookie: String) {
        cookies.setCookie(INSTAGRAM, sessionCookie)
        cookies.setCookie(INSTAGRAM, userCookie)
        cookies.flush()
    }

    companion object {
        /** The URL the cookie jar keys Instagram's cookies by; owned by `:instagram`. */
        const val INSTAGRAM = WebSessionCookies.ORIGIN

        /** First 12 hex characters of the SHA-256 of [sessionId]: enough to tell sessions apart, useless as a credential. */
        internal fun fingerprintOf(sessionId: String): String =
            MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8))
                .take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }

        private const val FINGERPRINT_BYTES = 6
    }
}
