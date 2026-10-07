package io.github.yuriimurha.reels.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Debug builds only (the Sync screen decides): developer tools. [mockMode] is the mode this process runs in (true: the
 * fake library), or null when there is no Mock mode switch, which hides the row; otherwise the whole row toggles it, and
 * [mockSwitchEnabled] is false while a run is RUNNING or the latest run has not loaded yet. [labEnabled] gates the Adapter
 * lab button.
 */
@Composable
fun DeveloperSection(
    mockMode: Boolean?,
    mockSwitchEnabled: Boolean,
    onMockModeChange: (Boolean) -> Unit,
    onOpenLab: () -> Unit,
    labEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Developer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (mockMode != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = mockMode, enabled = mockSwitchEnabled, role = Role.Switch, onValueChange = onMockModeChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mock mode (fake library)", Modifier.weight(1f))
                Switch(checked = mockMode, onCheckedChange = null, enabled = mockSwitchEnabled)
            }
            Text(
                "The app restarts. Real and fake libraries are kept separately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onOpenLab, enabled = labEnabled) { Text("Adapter lab") }
    }
}
