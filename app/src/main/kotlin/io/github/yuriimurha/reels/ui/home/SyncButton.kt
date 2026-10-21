package io.github.yuriimurha.reels.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import io.github.yuriimurha.reels.R
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.ui.common.motionEnabled

/**
 * What Home's sync button shows (the owner's design, 2026-10-21): the plain glyph ([IDLE]), the glyph turning while a run is
 * going ([RUNNING]), or a small red dot when something needs the owner ([ATTENTION]). [label] is the button's content
 * description and its tooltip. The button replaced the status chip; when the library last synced is said on the Sync screen.
 */
enum class SyncBadge(val label: String) {
    IDLE("Sync"),
    RUNNING("Sync, running"),
    ATTENTION("Sync, needs attention"),
    ;

    companion object {
        /**
         * The old chip's states, mapped: "Syncing…" (the latest run is RUNNING) is [RUNNING]; "⚠" and the `lastError` (a run
         * that ended unfinished, so the Sync screen offers Resume: PAUSED, among them a Cancel, and every STOPPED status, a
         * challenge, an expired session, a rate limit and "Adapter needs repair" alike) is [ATTENTION]; "Synced 5 min ago" and
         * "Not synced" are [IDLE]. A running run wins, as it did on the chip. A cooldown alone never made the chip warn, and
         * gives no dot.
         *
         * Beyond the chip, which read only the run: when the library needs a session ([requiresSession], the real library;
         * Mock mode's fake one needs none), a [session] that is not Valid (logged out, expired, challenged) is [ATTENTION]
         * too, since Sync stays off until the owner logs in. A session not read yet (null) is no dot: that would be a guess.
         */
        fun from(run: SyncRunEntity?, session: SessionState?, requiresSession: Boolean): SyncBadge = when {
            run?.status == SyncStatus.RUNNING -> RUNNING
            run != null && run.status.isResumable -> ATTENTION
            requiresSession && session != null && session !is SessionState.Valid -> ATTENTION
            else -> IDLE
        }
    }
}

/** The sync button's test tag (Compose tests and the emulator smoke suite). */
const val SYNC_BUTTON_TAG = "sync-button"

/** One turn of the glyph while a run is going: slow. */
internal const val SYNC_TURN_MS = 2_000

/**
 * Home's sync button: an icon with no caption, which opens Sync, in the viewer's icon-button pattern (a tooltip on a long
 * press, the same words as the content description). The glyph turns against the clock, the way its arrows point, while a run
 * is going; with the system's animations removed ([motionEnabled] false) it stays still and takes the primary colour instead.
 * A dot at its corner (Material's small [Badge], in the error colour) asks for attention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncButton(badge: SyncBadge, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val running = badge == SyncBadge.RUNNING
    val turning = running && motionEnabled(LocalContext.current)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(badge.label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.testTag(SYNC_BUTTON_TAG)) {
            BadgedBox(badge = { if (badge == SyncBadge.ATTENTION) Badge() }) {
                val angle = if (turning) {
                    rememberInfiniteTransition(label = "sync").animateFloat(
                        initialValue = 0f,
                        targetValue = -360f,
                        animationSpec = infiniteRepeatable(tween(SYNC_TURN_MS, easing = LinearEasing)),
                        label = "turn",
                    )
                } else {
                    null
                }
                Icon(
                    painterResource(R.drawable.ic_sync),
                    contentDescription = badge.label,
                    tint = if (running && !turning) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    // Read in the draw phase only: a turn redraws the glyph, it does not recompose the button.
                    modifier = Modifier.graphicsLayer { rotationZ = angle?.value ?: 0f },
                )
            }
        }
    }
}
