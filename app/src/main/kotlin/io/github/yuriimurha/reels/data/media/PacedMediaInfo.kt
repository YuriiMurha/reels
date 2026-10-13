package io.github.yuriimurha.reels.data.media

import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.session.userMessage
import io.github.yuriimurha.reels.sync.RunSession
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacerRefusal
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * One `mediaInfo` request on the Pacer's interactive lane, under the session gates of R76 and R79: the viewer's way to ask
 * Instagram for an item's fresh links (a reel's video, a carousel's items). Shared by [RealVideoSourceResolver] and
 * [CarouselRepository], so both ask the same way and under the same rules.
 *
 * Nothing is sent without a usable session: [isSessionReady] is asked before the request queues, and again from inside the
 * Pacer's gate together with the session layer's [SessionSignals.runSession] for the epoch the fetch started under, because
 * the wait for the gate can take seconds (R79). A login or challenge reply is passed on to the session layer under that epoch.
 */
class PacedMediaInfo(
    private val client: InstagramClient,
    private val pacer: Pacer,
    private val signals: SessionSignals,
    /** True only while the stored session is valid (R76). */
    private val isSessionReady: suspend () -> Boolean,
) {
    sealed interface Outcome {
        /** Instagram answered with the item's current links. */
        data class Fresh(val media: RemoteMedia) : Outcome

        /** `mediaInfo` returned null: Instagram no longer has the item. */
        data object Gone : Outcome

        /**
         * Nothing fresh. [reason] says what happened, so a caller can fall back to what it has cached and word the failure
         * for its own item; [message] is the part of the text that is the same for every caller (for [Reason.REFUSED], the
         * Pacer's own reason). It never holds an unexpected failure's own text (that may hold session data).
         */
        data class Failed(val message: String, val reason: Reason) : Outcome
    }

    enum class Reason {
        /** No usable session, before the request queued or from inside the gate: nothing was sent. */
        NO_SESSION,

        /** The Pacer refused (cooling down, the 24 h budget): nothing was sent. */
        REFUSED,

        /** Offline, or a transient failure on the way. */
        OFFLINE,

        /** Instagram asked for a login or a challenge; the session layer has been told. */
        NEEDS_ATTENTION,

        /** Instagram is limiting requests; the Pacer has armed the cooldown. */
        RATE_LIMITED,

        /** A changed reply shape, a failing session store, anything unforeseen. */
        UNEXPECTED,
    }

    /** The outcome of one `mediaInfo` for [pk]. Never throws except [CancellationException]. */
    suspend fun fetch(pk: String): Outcome {
        // The session this request starts under: a failure that outlives a logout or a paste must not expire the login after it.
        val epoch = try {
            signals.epoch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UNEXPECTED
        }
        return try {
            // Nothing may go to Instagram without a usable session (R76).
            if (!isSessionReady()) return NO_SESSION
            val media = try {
                // The request may wait seconds for the gate and the 2 s gap, and a challenge can arrive meanwhile: the Pacer asks
                // again from inside the gate, right before it would send and log anything (R79).
                // Not ready is also "no longer the session this fetch started under" (a logout, a paste, another account's login).
                pacer.interactive(
                    precondition = { if (!isSessionReady() || signals.runSession(epoch) != RunSession.USABLE) throw SessionNotReady() },
                ) { client.mediaInfo(pk) }
            } catch (e: SessionNotReady) {
                return NO_SESSION
            }
            if (media == null) Outcome.Gone else Outcome.Fresh(media)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PacerRefusal) {
            Outcome.Failed(e.userMessage("try again later"), Reason.REFUSED)
        } catch (e: InstagramException.Transient) {
            OFFLINE
        } catch (e: IOException) {
            OFFLINE
        } catch (e: InstagramException.LoginRequired) {
            notify { signals.loginRequired(epoch) }
            NEEDS_ATTENTION
        } catch (e: InstagramException.ChallengeRequired) {
            notify { signals.challengeRequired(e.challengeUrl, epoch) }
            NEEDS_ATTENTION
        } catch (e: InstagramException.RateLimited) {
            // The Pacer has already armed the cooldown.
            Outcome.Failed("Instagram is limiting requests", Reason.RATE_LIMITED)
        } catch (e: Exception) {
            // ShapeChanged, a session store that cannot be read, anything unforeseen. Its own text is not kept.
            UNEXPECTED
        }
    }

    /** The Pacer's precondition says the session is no longer usable: thrown inside the gate, caught right outside it. */
    private class SessionNotReady : Exception()

    /** A failing receiver must not change the outcome; cancellation still propagates. */
    private suspend fun notify(signal: suspend () -> Unit) {
        try {
            signal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Intentionally ignored: the caller already says that the session needs attention.
        }
    }

    companion object {
        /** What the viewer shows when the session needs the owner, whatever the item. */
        const val NEEDS_ATTENTION_MESSAGE = "Instagram session needs attention (Sync screen)"

        private val NO_SESSION = Outcome.Failed(NEEDS_ATTENTION_MESSAGE, Reason.NO_SESSION)
        private val NEEDS_ATTENTION = Outcome.Failed(NEEDS_ATTENTION_MESSAGE, Reason.NEEDS_ATTENTION)
        private val OFFLINE = Outcome.Failed("Offline", Reason.OFFLINE)
        private val UNEXPECTED = Outcome.Failed("Can't load this right now", Reason.UNEXPECTED)
    }
}
