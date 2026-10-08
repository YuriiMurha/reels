package io.github.yuriimurha.reels

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the guard that keeps [SmokeTest] off a physical phone. It only feeds strings to [isEmulator]: it starts no
 * Activity and reads no app state, so it is safe to run anywhere.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorDetectionTest {
    @Test
    fun realPhonesAreNotEmulators() {
        // Fingerprints, hardware and product names as released phones report them.
        assertFalse(isEmulator("google/husky/husky:15/AP4A.250205.002/12345678:user/release-keys", "husky", "husky"))
        assertFalse(isEmulator("samsung/dm1qxxx/dm1q:14/UP1A.231005.007/S911BXXU3BWL1:user/release-keys", "qcom", "dm1qxxx"))
        assertFalse(isEmulator("OnePlus/CPH2449/OP5958L1:14/UP1A.231005.007/T.18e0b3b_1:user/release-keys", "qcom", "CPH2449EEA"))
        assertFalse(isEmulator("Xiaomi/marble/marble:14/UKQ1.230804.001/V816.0.6.0.UMRMIXM:user/release-keys", "qcom", "marble_eea"))
    }

    /** A phone running a Generic System Image says "generic" in its fingerprint; that alone must not read as an emulator. */
    @Test
    fun aGsiPhoneIsNotAnEmulator() {
        assertFalse(isEmulator("google/gsi_arm64/generic_arm64:15/AP4A.250205.002/12345678:user/release-keys", "qcom", "gsi_arm64"))
    }

    /** "sdk" inside a word of a product name is not the SDK images' `sdk` token. */
    @Test
    fun aProductWithSdkInsideAWordIsNotAnEmulator() {
        assertFalse(isEmulator(fingerprint = "x", hardware = "qcom", product = "mysdkphone"))
        assertFalse(isEmulator(fingerprint = "x", hardware = "qcom", product = "sdkphone_eea"))
    }

    @Test
    fun emulatorsAreRecognisedByAnyOneSignal() {
        assertTrue(isEmulator("google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:user/release-keys", "ranchu", "sdk_gphone64_arm64"))
        assertTrue(isEmulator(fingerprint = "Android/emulator/emulator:14/x/1:eng/test-keys", hardware = "x", product = "x"))
        assertTrue(isEmulator(fingerprint = "x", hardware = "ranchu", product = "x"))
        assertTrue(isEmulator(fingerprint = "x", hardware = "goldfish", product = "x"))
        assertTrue(isEmulator(fingerprint = "x", hardware = "x", product = "sdk_gphone_x86"))
        assertTrue(isEmulator(fingerprint = "x", hardware = "x", product = "google_sdk"))
        assertTrue(isEmulator(fingerprint = "x", hardware = "x", product = "sdk"))
    }
}
