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

/** What the Sync screen offers for the latest run and the Pacer's state (spec 7.1, 7.4, 9.5). */
fun syncUiState(run: SyncRunEntity?, pacer: PacerStatus?, now: Long): SyncUiState {
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
    }
    return SyncUiState(
        running = running,
        resumable = resumable,
        canStart = !running && coolingUntil == null,
        canCancel = running,
        canDiscard = resumable && !running,
        canDeleteLibrary = !running,
        banner = banner,
    )
}
