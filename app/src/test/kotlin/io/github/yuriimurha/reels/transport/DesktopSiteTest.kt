package io.github.yuriimurha.reels.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R10: the repair page's identity is Chrome-on-Android's "Desktop site". The emulator test (`AndroidRepairPageTest`) shows the
 * WebView sends what [DesktopSite] says; these pin what it says.
 */
class DesktopSiteTest {
    private val site = checkNotNull(DesktopSite.of("145.0.7632.218"))

    @Test
    fun theUserAgentIsChromesDesktopSiteOneAtTheWebViewsMajor() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
            site.userAgent,
        )
        assertEquals(145, site.major)
        assertEquals("145.0.7632.218", site.fullVersion)
    }

    @Test
    fun theClientHintsAreChromiumsAndroidDesktopOnes() {
        assertEquals("Linux", DesktopSite.PLATFORM)
        assertEquals("", DesktopSite.PLATFORM_VERSION)
        assertEquals("x86", DesktopSite.ARCHITECTURE)
        assertEquals(64, DesktopSite.BITNESS)
        assertEquals("", DesktopSite.MODEL)
    }

    /**
     * The brands, GREASE included, in Chrome's order: the `sec-ch-ua` headers real Chrome sends for majors 120, 124, 131 and
     * 140 (each a different GREASE character pair, version and order), and the one this algorithm gives the emulator's 145.
     */
    @Test
    fun theBrandsAreChromesOwnForEachMajor() {
        for ((version, header) in mapOf(
            "120.0.6099.230" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
            "124.0.6367.207" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
            "131.0.6778.135" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "140.0.7339.207" to "\"Chromium\";v=\"140\", \"Not=A?Brand\";v=\"24\", \"Google Chrome\";v=\"140\"",
            "145.0.7632.218" to "\"Not:A-Brand\";v=\"99\", \"Google Chrome\";v=\"145\", \"Chromium\";v=\"145\"",
        )) {
            val brands = checkNotNull(DesktopSite.of(version)).brands
            assertEquals(header, brands.joinToString(", ") { "\"${it.name}\";v=\"${it.majorVersion}\"" }, version)
        }
    }

    @Test
    fun theFullVersionsAreTheWebViewsAndGreasesPadded() {
        assertEquals(
            listOf(
                DesktopSite.Brand("Not:A-Brand", "99", "99.0.0.0"),
                DesktopSite.Brand("Google Chrome", "145", "145.0.7632.218"),
                DesktopSite.Brand("Chromium", "145", "145.0.7632.218"),
            ),
            site.brands,
        )
    }

    @Test
    fun aVersionNameThatIsNotAVersionGivesNoIdentity() {
        for (name in listOf(null, "", "145", "145.0.7632", "145.0.7632.218-beta", "a.b.c.d", " 145.0.7632.218", "145.0.7632.218.1")) {
            assertNull(DesktopSite.of(name), "$name")
        }
    }
}
