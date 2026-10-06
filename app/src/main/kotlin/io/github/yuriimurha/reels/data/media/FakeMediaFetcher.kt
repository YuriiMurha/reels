package io.github.yuriimurha.reels.data.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.absoluteValue

/** Draws placeholder thumbnails for the fake backend, so no third-party images live in the repo. */
class FakeMediaFetcher : MediaFetcher {
    override suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.Default) {
        if (url.startsWith("fake://thumb/")) placeholder(url.substringAfterLast('/')) else null
    }

    private fun placeholder(pk: String): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = (pk.hashCode().absoluteValue % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
        val bottom = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.65f, 0.35f))
        canvas.drawPaint(
            Paint().apply { shader = LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), top, bottom, Shader.TileMode.CLAMP) },
        )
        canvas.drawText(
            pk.takeLast(4),
            WIDTH / 2f,
            HEIGHT / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 72f
                textAlign = Paint.Align.CENTER
            },
        )
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private companion object {
        const val WIDTH = 432
        const val HEIGHT = 768
    }
}
