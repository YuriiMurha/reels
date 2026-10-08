package io.github.yuriimurha.reels.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuriimurha.reels.instagram.lab.LabCall
import io.github.yuriimurha.reels.ui.LocalAppContainer
import java.io.File

/**
 * Debug-only (spec 6.3): one button per candidate endpoint, each sending ONE paced request and showing a redacted shape
 * of the answer. Reached from Sync's Developer section; the route only exists in debug builds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdapterLabScreen(
    onBack: () -> Unit,
    // A parameter only so tests can give the screen fakes; the app always uses the default.
    viewModel: AdapterLabViewModel = LocalAppContainer.current.let { container ->
        val labDir = File(LocalContext.current.filesDir, "lab")
        viewModel {
            AdapterLabViewModel(
                lab = AdapterLabRunner(container.adapterLab),
                pacer = container.instagramPacer,
                sessionState = container.session.state,
                signals = container.session,
                labDir = labDir,
                beforeCall = container::allowNewInstagramAttempts,
            )
        }
    },
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Adapter lab") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        AdapterLabContent(ui = ui, onRun = viewModel::tap, modifier = Modifier.padding(padding))
    }
}

@Composable
fun AdapterLabContent(ui: LabUiState, onRun: (LabCall) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Each tap sends one paced request to Instagram as the logged-in test account.", style = MaterialTheme.typography.bodyMedium)
        if (!ui.sessionValid) {
            Text("Log in on the Sync screen to use the lab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (call in LabCall.entries) {
            FilledTonalButton(onClick = { onRun(call) }, enabled = ui.canRun(call), modifier = Modifier.fillMaxWidth()) {
                Text(call.label())
            }
        }
        ui.running?.let {
            Text("Sending: ${it.label()}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        ui.shown?.let { LatestResult(it) }
    }
}

@Composable
private fun LatestResult(shown: LabShown) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Latest result: ${shown.call.label()}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("HTTP ${shown.httpCode}")
        Text("Classification: ${shown.classification}")
        // A shape line can be long: scroll sideways instead of wrapping it, and let the owner select text to copy.
        SelectionContainer {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Text(shown.shape, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
            }
        }
        shown.savedPath?.let {
            Text("Scrubbed copy: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
