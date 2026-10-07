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
)

internal const val LOG_IN_TO_SYNC = "Log in to Instagram to sync"

/**
 * What the Sync screen offers for the latest run and the Pacer's state (spec 7.1, 7.4, 9.5). [sessionReady] is false when
 * the backend talks to Instagram and the session is not known to be valid (not read yet, logged out, expired, challenged):
 * starting or resuming is then off, and the screen says why unless a more specific banner already does. While the stored
 * session is still being read ([sessionLoading]) starting stays off, but nothing is said: "Log in" would be a guess.
 */
fun syncUiState(
    run: SyncRunEntity?,
    pacer: PacerStatus?,
    now: Long,
    sessionReady: Boolean = true,
    sessionLoading: Boolean = false,
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
    )
}
