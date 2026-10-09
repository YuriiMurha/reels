package io.github.yuriimurha.reels.transport

/**
 * The identity Chrome on Android sends with "Desktop site" on (ruling R10), for a WebView whose package version is
 * [fullVersion] (`<major>.<minor>.<build>.<patch>`): the user agent, and the client hints the repair page sets through
 * `UserAgentMetadata`. The values are Chromium's own for Android in desktop mode (`components/embedder_support/
 * user_agent_utils.cc`): platform Linux with no platform version, architecture x86, bitness 64, no model, not mobile, form
 * factor Desktop; the brands are Chrome's (Chromium and Google Chrome at the WebView's version, and Chrome's GREASE brand), in
 * Chrome's order for that major version. Pure Kotlin, so its values are pinned on the JVM (`DesktopSiteTest`).
 */
internal class DesktopSite private constructor(val fullVersion: String) {
    /** The major version, the user agent's and the brands' (the user agent is a reduced one: `<major>.0.0.0`). */
    val major: Int = fullVersion.substringBefore('.').toInt()

    val userAgent: String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

    /** The brands in the order Chrome sends them (`sec-ch-ua`, and with full versions `sec-ch-ua-full-version-list`). */
    val brands: List<Brand> = brandsOf(major, fullVersion)

    /** One brand: its name, its major version (`sec-ch-ua`) and its full version (`sec-ch-ua-full-version-list`). */
    data class Brand(val name: String, val majorVersion: String, val fullVersion: String)

    companion object {
        const val PLATFORM = "Linux"

        /** Chrome's desktop mode on Android sends no platform version (Chromium's `GetPlatformVersion` returns ""). */
        const val PLATFORM_VERSION = ""
        const val ARCHITECTURE = "x86"
        const val BITNESS = 64
        const val MODEL = ""

        private val VERSION = Regex("""[0-9]{1,4}(\.[0-9]{1,6}){3}""")

        /** The identity for a WebView whose package's `versionName` is [versionName]; null when that is not a version. */
        fun of(versionName: String?): DesktopSite? = versionName?.takeIf { VERSION.matches(it) }?.let(::DesktopSite)

        /** Chromium's GREASE characters and versions, and its orders of three brands (`GetGreasedUserAgentBrandVersion`). */
        private val GREASE_CHARACTERS = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        private val GREASE_VERSIONS = listOf("8", "99", "24")
        private val ORDERS = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))

        /**
         * Chrome's brand list, seeded by the major version as Chromium's `GenerateBrandVersionList` does: the GREASE brand,
         * Chromium and Google Chrome, in that order, with the one at position `i` moved to `order[i]`.
         */
        private fun brandsOf(major: Int, fullVersion: String): List<Brand> {
            val greaseName = "Not" + GREASE_CHARACTERS[major % GREASE_CHARACTERS.size] + "A" +
                GREASE_CHARACTERS[(major + 1) % GREASE_CHARACTERS.size] + "Brand"
            val greaseVersion = GREASE_VERSIONS[major % GREASE_VERSIONS.size]
            val listed = listOf(
                Brand(greaseName, greaseVersion, "$greaseVersion.0.0.0"),
                Brand("Chromium", "$major", fullVersion),
                Brand("Google Chrome", "$major", fullVersion),
            )
            val order = ORDERS[major % ORDERS.size]
            val shuffled = arrayOfNulls<Brand>(listed.size)
            listed.forEachIndexed { i, brand -> shuffled[order[i]] = brand }
            return shuffled.map { checkNotNull(it) }
        }
    }
}
