package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.WebSessionCookies
import io.github.yuriimurha.reels.instagram.web.cookieValue
import io.github.yuriimurha.reels.sync.RunSession
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
import java.util.concurrent.atomic.AtomicReference

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
     * Bumped, under [lock], whenever the jar's session is replaced or forgotten: logout, a paste and a paste's rollback, and
     * a [validate] that finds a session other than the one the epoch was issued for (the owner logged in again in the WebView,
     * possibly as another account). Volatile so [epoch] can be read without the lock, at the start of a run, even while a paste
     * holds it.
     */
    @Volatile
    private var sessionEpoch = 0

    /**
     * The session a [sessionEpoch] was issued for: the fingerprint of its sessionid (null for none) and the account it belongs to
     * (`ds_user_id`, which Instagram keeps when it re-issues a sessionid).
     */
    private class Issued(val fingerprint: String?, val userId: String?)

    private fun jarSession() = Issued(currentSessionFingerprint(), userId())

    /**
     * What the current epoch was issued for. Null until something needs it: nothing reads the jar when the repository is built
     * (that would load the WebView), so epoch 0 is recorded by the first [epoch] call, which is what a run holds from then on,
     * or else by the first [validate] or [runSession], when nobody holds it. Every bump records its own session, under [lock],
     * once the jar is written. Only ever read for a decision under [lock] or by [runSession]; the one write outside the lock is
     * a compare-and-set from null.
     */
    private val issuedFor = AtomicReference<Issued?>(null)

    /**
     * The epoch a run (or a lab call, or a video resolve) starts under, and the moment the jar's session is remembered for it
     * the first time. It never throws: `CookieManager` fails without a WebView provider (while it is being updated, say), and
     * this is called outside every try. Then nothing is recorded, and the next call or the in-gate [runSession] tries again.
     */
    override fun epoch(): Int {
        if (issuedFor.get() == null) {
            try {
                issuedFor.compareAndSet(null, jarSession())
            } catch (e: Exception) {
                // Not recorded: retried by the next epoch(), validate() or runSession().
            }
        }
        return sessionEpoch
    }

    override fun currentSessionFingerprint(): String? = sessionId()?.let(::fingerprintOf)

    private fun sessionId(): String? = cookies.cookieValue(INSTAGRAM, "sessionid")

    private fun userId(): String? = cookies.cookieValue(INSTAGRAM, "ds_user_id")

    override fun hasSessionCookies(): Boolean = sessionId() != null && userId() != null

    override fun hasCsrfToken(): Boolean = cookies.cookieValue(INSTAGRAM, "csrftoken") != null

    /**
     * One paced request on the interactive lane. Network, rate-limit and budget failures propagate unchanged.
     * Without session cookies there is nothing to check: the state becomes LoggedOut and no request is made.
     * A result that arrives after a logout, a paste or a new login replaced the session is discarded.
     *
     * A session the epoch was not issued for (the jar's sessionid changed since: a WebView login, as another account too)
     * starts a new epoch, so a sync run that began under the old one stops at its next request instead of carrying on with
     * the new cookies. A check of the SAME session leaves the epoch alone, so Check now never stops a running sync. A
     * sessionid that Instagram itself rotates looks like a new login here: that stops a run, which is the safe direction.
     *
     * Lock order: [lock] is not held across the request, so a logout never waits for the network. The epoch is therefore
     * settled in the first locked block (before the request, so the old run is stopped as soon as the new session is seen, not
     * when its check ends) and again in the second (so a change during the request discards the answer), each time BEFORE
     * [store] writes anything.
     */
    override suspend fun validate(): SessionState {
        val (epoch, handle) = lock.withLock {
            // Before anything else, and before the request: it may wait seconds for the Pacer, and a run that sleeps in a break
            // resumes meanwhile, still seeing the old session's Valid state. A different session in the jar ends its epoch here.
            startEpochIfSessionChanged()
            if (!hasSessionCookies()) return store(SessionState.LoggedOut)
            sessionEpoch to state.first().handle
        }
        val result = probeSession(handle)
        return lock.withLock {
            // Again, for a login that landed while the request was out: the answer then belongs to a session that is gone.
            startEpochIfSessionChanged()
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
            recordIssuedFor()
            suspend fun rollBack() = withContext(NonCancellable) {
                writeSessionCookies(WebSessionCookies.sessionCookie(previousSession), WebSessionCookies.userCookie(previousUser))
                sessionEpoch++
                recordIssuedFor()
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
            recordIssuedFor()
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

    /**
     * R82: may work that started under [epoch] (a sync run, a lab call, a video resolve) send its next request? A stored
     * Challenge says [RunSession.CHALLENGE]; only a Valid state under the same epoch, with the jar still holding the ACCOUNT
     * the epoch was issued for, is [RunSession.USABLE]; anything else (Expired, LoggedOut, a logout, a paste or a new login
     * since the work started, another account's cookies in the jar, or a jar that lost its account) is [RunSession.NOT_USABLE].
     * A read, never a request, and it changes nothing.
     *
     * The account check is what closes the gap before a check has seen a WebView login as another account: the epoch only ends
     * in [validate], which the login screen calls up to a second after the new cookies arrive (and not at all if the owner has
     * left it). The jar's `ds_user_id` is compared, not the sessionid: Instagram re-issuing a sessionid for the same account
     * must not stop work.
     *
     * The engine asks this from INSIDE the Pacer's gate. It must never take [lock]: a paste holds the lock while it waits for
     * that same gate, so taking it here would leave each waiting for the other. It needs no lock: [sessionEpoch] is volatile
     * and the state is one DataStore read. The state is read first, the epoch second, the jar last: logout, paste and a new
     * login's check bump the epoch before they record anything else, so one that has begun is always seen. One request can still
     * go out under the new account if the cookies change between this check and OkHttp reading them a moment later.
     */
    override suspend fun runSession(epoch: Int): RunSession {
        val stored = state.first()
        return when {
            stored is SessionState.Challenge -> RunSession.CHALLENGE
            stored is SessionState.Valid && epoch == sessionEpoch && jarStillHoldsTheEpochsAccount() -> RunSession.USABLE
            else -> RunSession.NOT_USABLE
        }
    }

    private fun jarStillHoldsTheEpochsAccount(): Boolean = userId() == recordedOrRecord(jarSession()).userId

    private suspend fun probeSession(handle: String?): SessionState = try {
        SessionState.Valid(pacer.interactive { probe.currentUser() }.username)
    } catch (e: InstagramException.LoginRequired) {
        SessionState.Expired(handle)
    } catch (e: InstagramException.ChallengeRequired) {
        SessionState.Challenge(e.challengeUrl, handle)
    }

    /** Under [lock]: the epoch just bumped was issued for whatever session the jar holds now. */
    private fun recordIssuedFor() = issuedFor.set(jarSession())

    /**
     * Under [lock]: ends the current epoch if the jar holds a session other than the one it was issued for. The epoch moves
     * before the caller stores anything, and a run reads the stored state first and the epoch second (see [runSession]), so
     * a run that sees the old state still sees the new epoch.
     */
    private fun startEpochIfSessionChanged() {
        val now = jarSession()
        // Nothing has asked for the epoch yet (no run, no lab call): nobody holds it, so there is nothing to stop. If a run
        // asked in between, its record wins, and the comparison below is made against that.
        val issued = recordedOrRecord(now)
        if (issued.fingerprint == now.fingerprint && issued.userId == now.userId) return
        sessionEpoch++
        issuedFor.set(now)
    }

    /** What the current epoch was issued for; [ifNone] is recorded first when nothing has been (see [issuedFor]). */
    private fun recordedOrRecord(ifNone: Issued): Issued {
        issuedFor.compareAndSet(null, ifNone)
        return issuedFor.get()!!
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
