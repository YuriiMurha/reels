package io.github.yuriimurha.reels

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Numbered PNGs of the screen at each step of one test, in the app's external files dir:
 * `/sdcard/Android/data/io.github.yuriimurha.reels/files/smoke/<prefix>-01-<label>.png`. The files of an earlier run of
 * the same test are removed when the test starts, so the folder always holds the latest run.
 */
internal class Screenshots(private val rule: ComposeTestRule, private val prefix: String) {
    private val dir: File = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("smoke")) {
        "no external files dir on this device"
    }.also { it.mkdirs() }
    private var next = 1

    init {
        dir.listFiles { file -> file.name.startsWith("$prefix-") }?.forEach { it.delete() }
    }

    fun take(label: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val file = File(dir, "%s-%02d-%s.png".format(prefix, next++, label))
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "could not write ${file.name}" } }
    }
}
