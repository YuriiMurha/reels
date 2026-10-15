package io.github.yuriimurha.reels.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import java.io.File

/**
 * A cached local thumbnail, or a quiet placeholder when there is none (spec 5.1: null renders a placeholder).
 *
 * [version] says which picture [path] holds (for a post's cover, its cover link): when it changes, the picture is asked for
 * again, even under the same path. R29: a sync drops a changed post's cover and writes the new one under the same `{pk}.jpg`.
 * When both rows reach the screen in one recomposition (the app in the background while the sync ran), the path never
 * changes on screen, and Coil, handed an equal model, would go on drawing the old picture it holds. A new version makes a new
 * request, which the app's loader keys by the file's time and size (`LocalFileKeyer`): a rewritten file loads anew, an
 * unchanged one (a cover link Instagram only signed again) comes from memory.
 */
@Composable
fun Thumbnail(path: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, version: Any? = null) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    if (path == null) {
        Box(modifier.background(placeholderColor))
    } else {
        val placeholder = ColorPainter(placeholderColor)
        key(path, version) {
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
}
