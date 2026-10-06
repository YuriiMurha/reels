package io.github.yuriimurha.reels.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.io.File

/** A cached local thumbnail, or a quiet placeholder when there is none (spec 5.1: null renders a placeholder). */
@Composable
fun Thumbnail(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    if (path == null) {
        Box(modifier.background(placeholderColor))
    } else {
        val placeholder = ColorPainter(placeholderColor)
        AsyncImage(
            model = File(path),
            contentDescription = null,
            modifier = modifier,
            placeholder = placeholder,
            error = placeholder,
            contentScale = contentScale,
        )
    }
}
