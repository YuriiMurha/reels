package io.github.yuriimurha.reels

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * The two safety checks every smoke test passes before it touches the app. The suite is for the emulator, in Mock mode
 * (the built-in fake library); it must never run against a physical phone (a real library, a real login) and never reach
 * Instagram.
 */

internal const val NOT_AN_EMULATOR =
    "The smoke tests run on the emulator only: this looks like a physical device, so they are skipped"

internal const val MOCK_MODE_OFF =
    "Turn Mock mode on (Sync → Developer) before running the smoke tests. They only ever use the fake library; " +
        "this process runs on the real one."

/**
 * True on an Android emulator or a virtual device. The inputs are parameters (defaulting to this device's [Build] values)
 * so [EmulatorDetectionTest] can feed it what a real phone reports.
 *
 * Deliberately NOT a match on "generic": a real phone running a Generic System Image (fingerprint
 * `google/gsi_arm64/generic_arm64:...`) says that too. What only emulators say is `ro.hardware` ranchu/goldfish, an
 * `sdk_gphone` or `emulator` fingerprint, and a product named from the SDK images: `sdk_gphone64_arm64`, `sdk_x86`,
 * `google_sdk`. The product is compared by its `_`-separated tokens, so a phone whose name merely contains the letters
 * "sdk" somewhere inside a word does not match.
 */
internal fun isEmulator(
    fingerprint: String = Build.FINGERPRINT,
    hardware: String = Build.HARDWARE,
    product: String = Build.PRODUCT,
): Boolean =
    fingerprint.lowercase().let { "emulator" in it || "sdk_gphone" in it } ||
        hardware.lowercase() in setOf("ranchu", "goldfish") ||
        "sdk" in product.lowercase().split('_')

/**
 * Fails (not skips) when this process runs on the real library. `usesFake` is read once per process from the stored
 * choice, so this is exactly what the app is doing right now. A test must never flip the switch: that restarts the process.
 */
internal fun requireMockMode() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ReelsApp
    assertTrue("This is a release build; the smoke tests need the debug build with Mock mode.", BuildConfig.DEBUG)
    assertTrue(MOCK_MODE_OFF, app.container.usesFake)
}

/** Skips on a physical device, fails when Mock mode is off. Both before anything is launched or tapped. */
internal fun requireSafeTarget() {
    assumeTrue(NOT_AN_EMULATOR, isEmulator())
    requireMockMode()
}

/**
 * Runs [requireSafeTarget] around everything inside it. Put it OUTERMOST in a rule chain: the Activity is then not even
 * launched on a physical phone or in real mode, and the notification permission is not granted there.
 */
class SmokeGuard : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            requireSafeTarget()
            base.evaluate()
        }
    }
}

/** The sync runs as a foreground worker: without this, Android 13+ shows a permission dialog on the first Sync. */
internal fun notificationPermissionRule(): GrantPermissionRule =
    if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS) else GrantPermissionRule.grant()
