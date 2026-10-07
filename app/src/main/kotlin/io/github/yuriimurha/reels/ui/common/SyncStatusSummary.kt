package io.github.yuriimurha.reels.ui.common

import android.text.format.DateUtils
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus

sealed interface SyncStatusSummary {
    data object Never : SyncStatusSummary
    data object Running : SyncStatusSummary
    data class Problem(val text: String) : SyncStatusSummary
    data class SyncedAt(val at: Long) : SyncStatusSummary

    companion object {
        fun from(run: SyncRunEntity?, lastSyncAt: Long?): SyncStatusSummary = when {
            run?.status == SyncStatus.RUNNING -> Running
            run != null && run.status.isResumable -> Problem(run.lastError ?: "Sync paused")
            lastSyncAt != null -> SyncedAt(lastSyncAt)
            else -> Never
        }
    }
}

@Composable
fun SyncStatusChip(status: SyncStatusSummary, onClick: () -> Unit) {
    val label = when (status) {
        SyncStatusSummary.Never -> "Not synced"
        SyncStatusSummary.Running -> "Syncing…"
        is SyncStatusSummary.Problem -> "⚠ ${status.text}"
        is SyncStatusSummary.SyncedAt -> "Synced " +
            DateUtils.getRelativeTimeSpanString(status.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    }
    AssistChip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.widthIn(max = 200.dp),
    )
}
