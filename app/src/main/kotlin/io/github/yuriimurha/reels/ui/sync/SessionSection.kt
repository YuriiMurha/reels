package io.github.yuriimurha.reels.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yuriimurha.reels.session.SessionState

@Composable
fun SessionSection(
    state: SessionState,
    message: String?,
    onLogin: () -> Unit,
    onResolveChallenge: (String?) -> Unit,
    onLogout: () -> Unit,
    onCheck: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Instagram session", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        when (state) {
            SessionState.LoggedOut -> {
                Text("Not logged in")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLogin) { Text("Log in") }
                    TextButton(onClick = onPaste) { Text("Paste sessionid") }
                }
            }
            is SessionState.Valid -> {
                Text("Logged in as @${state.handle}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheck) { Text("Check now") }
                    TextButton(onClick = onLogout) { Text("Log out") }
                }
            }
            is SessionState.Expired -> {
                Text("Session expired" + (state.handle?.let { " (@$it)" } ?: ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLogin) { Text("Log in again") }
                    TextButton(onClick = onPaste) { Text("Paste sessionid") }
                }
            }
            is SessionState.Challenge -> {
                Text("Instagram wants verification")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onResolveChallenge(state.challengeUrl) }) { Text("Resolve on Instagram") }
                    TextButton(onClick = onCheck) { Text("Check now") }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** The fallback from spec D3. The value is hidden while typing and is stored only in the WebView's cookie jar. */
@Composable
fun PasteSessionDialog(error: String?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste sessionid") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Copy it from a mobile browser on this phone that is logged into the test account.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    // A password field: keyboards neither suggest from it nor learn it.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(text) }, enabled = text.isNotBlank()) { Text("Use it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
