package io.github.yuriimurha.reels.ui.sync

import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.sync.pacing.PacerStatus

data class SyncUiState(
    val running: Boolean,
    val resumable: Boolean,
    val canStart: Boolean,
    val canCancel: Boolean,
    val canDiscard: Boolean,
    val canDeleteLibrary: Boolean,
    val banner: String?,
    /** Said beside the banner, never instead of it: the collection names are the last good ones. Blocks nothing. */
    val collectionNamesNotice: String? = null,
)

internal const val LOG_IN_TO_SYNC = "Log in to Instagram to sync"

/** Spec 2026-10-09 §3.3: a sync kept the last collection names because it could not refresh them. */
internal const val COLLECTION_NAMES_STALE = "Couldn't refresh collection names"

/**
 * What the Sync screen offers for the latest run and the Pacer's state (spec 7.1, 7.4, 9.5). [sessionReady] is false when
 * the backend talks to Instagram and the session is not known to be valid (not read yet, logged out, expired, challenged):
 * starting or resuming is then off, and the screen says why unless a more specific banner already does. While the stored
 * session is still being read ([sessionLoading]) starting stays off, but nothing is said: "Log in" would be a guess.
 * [collectionNamesStale] is the real library's flag (spec 2026-10-09 §3.3).
 */
fun syncUiState(
    run: SyncRunEntity?,
    pacer: PacerStatus?,
    now: Long,
    sessionReady: Boolean = true,
    sessionLoading: Boolean = false,
    collectionNamesStale: Boolean = false,
): SyncUiState {
    val running = run?.status == SyncStatus.RUNNING
    val resumable = run?.status?.isResumable == true
    val coolingUntil = pacer?.cooldownUntil?.takeIf { it > now }
    val banner = when {
        coolingUntil != null -> "Cooling down after a rate limit: ${(coolingUntil - now + 59_999) / 60_000} min left"
        run == null -> null
        else -> when (run.status) {
            SyncStatus.STOPPED_CHALLENGE -> "Instagram wants verification. Resolve it before syncing again."
            SyncStatus.STOPPED_LOGIN -> "Session expired. Log in again, then tap Resume."
            SyncStatus.STOPPED_SHAPE -> run.lastError ?: "Adapter needs repair"
            SyncStatus.STOPPED_RATE_LIMIT -> "Instagram limited requests. Tap Resume when you're ready."
            SyncStatus.PAUSED -> run.lastError ?: "Paused"
            SyncStatus.RUNNING, SyncStatus.DONE, SyncStatus.CANCELLED -> null
        }
    } ?: LOG_IN_TO_SYNC.takeIf { !sessionReady && !sessionLoading }
    return SyncUiState(
        running = running,
        resumable = resumable,
        canStart = !running && coolingUntil == null && sessionReady,
        canCancel = running,
        canDiscard = resumable && !running,
        canDeleteLibrary = !running,
        banner = banner,
        collectionNamesNotice = COLLECTION_NAMES_STALE.takeIf { collectionNamesStale },
    )
}

/**
 * Mock mode's extra line under the session status: the REAL Pacer's state, because Check now, the Adapter lab and the video
 * resolver send real requests through it even while the library is the fake one (whose own Pacer drives everything else on
 * the screen). A cooldown that is still running replaces the count. Null until the status has been read. The minutes round
 * up, like the cooldown banner's.
 */
fun realPacerLine(status: PacerStatus?, now: Long): String? {
    status ?: return null
    val coolingUntil = status.cooldownUntil?.takeIf { it > now }
        ?: return "Instagram requests in 24 h: ${status.requestsLast24h} / ${status.dailyBudget}"
    return "Instagram requests paused: ${(coolingUntil - now + 59_999) / 60_000} min left (cooldown)"
}
