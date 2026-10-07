package io.github.yuriimurha.reels.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import io.github.yuriimurha.reels.sync.pacing.PacerStatus
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.login.LoginPurpose

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    onOpenLogin: (url: String?, purpose: LoginPurpose) -> Unit,
    // A parameter only so tests can give the screen a fake session; the app always uses the default.
    viewModel: SyncViewModel = LocalAppContainer.current.let { container ->
        viewModel { SyncViewModel(container.syncController, container.library, container.backend.pacer, container.session) }
    },
) {
    val context = LocalContext.current
    val run by viewModel.run.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pacer by viewModel.pacerStatus.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val lastFull by viewModel.lastFullSyncAt.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val sessionMessage by viewModel.sessionMessage.collectAsStateWithLifecycle()
    val pasteError by viewModel.pasteError.collectAsStateWithLifecycle()
    var pasting by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val startSync: (SyncMode) -> Unit = { mode ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.start(mode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        SyncContent(
            run = run,
            ui = ui,
            pacer = pacer,
            lastSyncAt = lastSync,
            lastFullSyncAt = lastFull,
            onSync = { startSync(SyncMode.QUICK) },
            onFullSync = { startSync(SyncMode.FULL) },
            onCancel = viewModel::cancel,
            onDiscard = viewModel::discard,
            onDeleteLibrary = { confirmDelete = true },
            modifier = Modifier.padding(padding),
            sessionSection = {
                SessionSection(
                    state = sessionState,
                    message = sessionMessage,
                    onLogin = { onOpenLogin(null, LoginPurpose.LOGIN) },
                    onRelogin = { onOpenLogin(null, LoginPurpose.RELOGIN) },
                    onResolveChallenge = { url -> onOpenLogin(url, LoginPurpose.CHALLENGE) },
                    onLogout = viewModel::logout,
                    onCheck = viewModel::checkSession,
                    onPaste = { viewModel.clearPasteError(); pasting = true },
                )
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete library?") },
            text = {
                Text("Removes every synced item and thumbnail from this phone. Getting them back takes a full, paced sync. Your Instagram session is kept.")
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deleteLibrary() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }

    if (pasting) {
        PasteSessionDialog(
            error = pasteError,
            onSubmit = { input ->
                viewModel.paste(input) { needsCsrf ->
                    pasting = false
                    if (needsCsrf) onOpenLogin(WebEndpoints.HOME_URL, LoginPurpose.CSRF)
                }
            },
            onDismiss = { pasting = false },
        )
    }
}

@Composable
fun SyncContent(
    run: SyncRunEntity?,
    ui: SyncUiState,
    pacer: PacerStatus?,
    lastSyncAt: Long?,
    lastFullSyncAt: Long?,
    onSync: () -> Unit,
    onFullSync: () -> Unit,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onDeleteLibrary: () -> Unit,
    modifier: Modifier = Modifier,
    sessionSection: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ui.banner?.let { Banner(it) }
        sessionSection()
        Section("Library") {
            if (ui.resumable) {
                Button(onClick = onSync, enabled = ui.canStart, modifier = Modifier.fillMaxWidth()) { Text("Resume") }
                OutlinedButton(onClick = onDiscard, enabled = ui.canDiscard, modifier = Modifier.fillMaxWidth()) {
                    Text("Discard paused run")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSync, enabled = ui.canStart, modifier = Modifier.weight(1f)) { Text("Sync") }
                    OutlinedButton(onClick = onFullSync, enabled = ui.canStart, modifier = Modifier.weight(1f)) { Text("Full sync") }
                }
            }
            if (ui.canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
            Text(
                "Sync adds new saves. Full sync also removes unsaves and applies moves between collections.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        run?.let { RunProgress(it, pacer) }
        Section("History") {
            Line("Last sync", lastSyncAt.relative())
            Line("Last full sync", lastFullSyncAt.relative())
        }
        Section("Storage") {
            OutlinedButton(onClick = onDeleteLibrary, enabled = ui.canDeleteLibrary) { Text("Delete library") }
        }
    }
}

@Composable
private fun RunProgress(run: SyncRunEntity, pacer: PacerStatus?) {
    Section(if (run.status == SyncStatus.RUNNING) "Syncing" else "Last run") {
        if (run.phase.isNotBlank()) Text(run.phase, style = MaterialTheme.typography.bodyMedium)
        Line("Collections", "${run.collectionsDone} / ${run.collectionsTotal}")
        Line("New items", run.newItems.toString())
        Line("Items seen", run.seenItems.toString())
        Line("Thumbnails cached", run.thumbsCached.toString())
        Line("Failures", run.failures.toString())
        if (pacer != null) {
            Line("Requests this run", "${run.requestsUsed} / ${pacer.perRunBudget}")
            Line("Requests in 24 h", "${pacer.requestsLast24h} / ${pacer.dailyBudget}")
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
    )
}

private fun Long?.relative(): String = this?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "Never"
