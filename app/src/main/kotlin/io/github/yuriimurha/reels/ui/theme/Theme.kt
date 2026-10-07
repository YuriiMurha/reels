package io.github.yuriimurha.reels.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ReelsColors = darkColorScheme(
    primary = Color(0xFFF2C14E),
    onPrimary = Color(0xFF1A1300),
    secondary = Color(0xFF9AA4B2),
    background = Color(0xFF0B0B0C),
    onBackground = Color(0xFFECE8E1),
    surface = Color(0xFF141416),
    onSurface = Color(0xFFECE8E1),
    surfaceVariant = Color(0xFF1E1F22),
    onSurfaceVariant = Color(0xFFA9A49B),
    error = Color(0xFFFF6B6B),
)

/** Dark only (spec 9). */
@Composable
fun ReelsTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = ReelsColors, content = content)
