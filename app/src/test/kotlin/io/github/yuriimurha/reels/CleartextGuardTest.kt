package io.github.yuriimurha.reels

import io.github.yuriimurha.reels.testutil.KotlinSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source pins (in the style of [WebViewDebuggingGuardTest]) that the shipped app speaks HTTPS only. The emulator tests of the
 * hidden page (`AndroidWebPageTest`) talk plain HTTP to a local server on 127.0.0.1, which the platform allows for loopback on
 * its own (checked on the emulator: `NetworkSecurityPolicy.isCleartextTrafficPermitted("127.0.0.1")` is true with no
 * configuration). Nobody should therefore ever need to switch cleartext on for them, and these pins make sure no one does in a
 * way that reaches a release build: not in `src/main`, not in a release or flavour source set, and in `src/debug` (if a
 * lower-API emulator ever needs it) for loopback only. Never a self-signed HTTPS server with an SSL-error override either.
 */
class CleartextGuardTest {
    private val src = File("src")

    /** Every manifest and every `res/xml` file of source set [name]: where cleartext policy can be written. */
    private fun policyFiles(name: String): List<File> {
        val set = File(src, name)
        val manifest = listOf(File(set, "AndroidManifest.xml"))
        val xml = File(set, "res").walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile?.name?.startsWith("xml") == true }.toList()
        return (manifest + xml).filter { it.isFile }
    }

    /** The source sets that end up in a release build: everything but the two test sets and `debug`. */
    private fun releaseSourceSets(): List<String> {
        assertTrue(src.isDirectory, "unit tests must run from the app module directory")
        return src.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.filterNot { it in setOf("test", "androidTest", "debug") }
    }

    /**
     * True when [xml] gives attribute [name] any value but the literal `false`: `true`, and also a manifest placeholder
     * (`${'$'}{allowCleartext}`) or a resource reference (`@bool/x`), which a build can turn into `true`.
     */
    private fun setToAnythingButFalse(xml: String, name: String) =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""").findAll(xml).any { it.groupValues[1] != "false" }

    private fun permitsCleartext(xml: String) =
        setToAnythingButFalse(xml, "usesCleartextTraffic") || setToAnythingButFalse(xml, "cleartextTrafficPermitted")

    private fun namesANetworkConfig(xml: String) = Regex("""\bnetworkSecurityConfig\s*=""").containsMatchIn(xml)

    @Test
    fun noReleaseSourceSetPermitsCleartextOrNamesANetworkConfig() {
        val sets = releaseSourceSets()
        assertTrue("main" in sets, "the scan found $sets")
        val files = sets.flatMap { policyFiles(it) }
        // The scan reads what it should: main's manifest and the one xml resource it has.
        assertTrue(File("src/main/AndroidManifest.xml") in files && File("src/main/res/xml/data_extraction_rules.xml") in files, "scanned: $files")
        for (file in files) {
            val text = file.readText()
            assertFalse(permitsCleartext(text), "${file.path} permits cleartext traffic: a release build must speak HTTPS only")
            assertFalse(namesANetworkConfig(text), "${file.path} names a network security config: release has none, and a debug one belongs in src/debug")
        }
    }

    /** The KDoc's "never a self-signed HTTPS server with an SSL-error override": nothing that ships (or runs in debug) overrides it. */
    @Test
    fun noSourceSetOverridesSslErrors() {
        val sets = releaseSourceSets() + "debug"
        val files = sets.flatMap { File(src, it).walkTopDown().filter { f -> f.isFile && f.extension in setOf("kt", "java") }.toList() }
        assertTrue(files.size > 50, "the scan found ${files.size} files")
        assertEquals(emptyList(), files.filter { overridesSslErrors(it.readText()) }.map { it.path })
        // The scan itself: a real override is seen, a mention in a comment is not.
        assertTrue(overridesSslErrors("override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.proceed() }"))
        assertTrue(overridesSslErrors("public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) { h.proceed(); }"))
        assertFalse(overridesSslErrors("// never override onReceivedSslError\n/* onReceivedSslError */ val x = 1"))
    }

    private fun overridesSslErrors(text: String) = "onReceivedSslError" in KotlinSource.code(text)

    /**
     * The only cleartext a debug build may allow is to the machine itself: the hosts the tests' local servers answer on.
     * Today there is no debug config at all (and so nothing to check but the manifest); the scanner below proves the check bites.
     */
    @Test
    fun aDebugOnlyConfigMayPermitCleartextForLoopbackOnly() {
        for (file in policyFiles("debug")) assertEquals(emptyList(), cleartextTargets(file.readText()).filterNot { it in LOOPBACK }, file.path)
    }

    @Test
    fun theScannersSeeEveryFormOfPermission() {
        for (text in listOf(
            """<application android:usesCleartextTraffic="true" />""",
            """<application android:usesCleartextTraffic = 'true'/>""",
            """<base-config cleartextTrafficPermitted="true" />""",
            """<domain-config cleartextTrafficPermitted="true"><domain>192.0.2.7</domain></domain-config>""",
            // Anything but a literal false counts: a placeholder or a resource a build can turn into true.
            """<application android:usesCleartextTraffic="${'$'}{allowCleartext}" />""",
            """<application android:usesCleartextTraffic="@bool/cleartext" />""",
            """<application android:usesCleartextTraffic="" />""",
            """<base-config cleartextTrafficPermitted="${'$'}{x}" />""",
        )) assertTrue(permitsCleartext(text), text)
        for (text in listOf(
            """<application android:usesCleartextTraffic="false" />""",
            """<base-config cleartextTrafficPermitted="false" />""",
            """<application android:allowBackup="false" android:label="usesCleartextTraffic" />""",
        )) assertFalse(permitsCleartext(text), text)
        assertTrue(namesANetworkConfig("""<application android:networkSecurityConfig="@xml/network_security_config" />"""))
        assertFalse(namesANetworkConfig("""<application android:label="@string/app_name" />"""))

        // What a config permits: the domains of a cleartext-permitting domain-config, or EVERYTHING for a permitting base-config.
        assertEquals(
            listOf("127.0.0.1", "localhost"),
            cleartextTargets("""<domain-config cleartextTrafficPermitted="true"><domain>127.0.0.1</domain><domain includeSubdomains="false">localhost</domain></domain-config>"""),
        )
        assertEquals(listOf("everything"), cleartextTargets("""<base-config cleartextTrafficPermitted="true"/>"""))
        assertEquals(listOf("everything"), cleartextTargets("""<application android:usesCleartextTraffic="true"/>"""))
        assertEquals(listOf("everything"), cleartextTargets("""<application android:usesCleartextTraffic="${'$'}{allowCleartext}"/>"""))
        assertEquals(listOf("everything"), cleartextTargets("""<base-config cleartextTrafficPermitted="@bool/x"/>"""))
        assertEquals(listOf("10.0.2.2"), cleartextTargets("""<domain-config cleartextTrafficPermitted="${'$'}{x}"><domain>10.0.2.2</domain></domain-config>"""))
        assertEquals(listOf("192.0.2.7"), cleartextTargets("""<domain-config cleartextTrafficPermitted="true"><domain>192.0.2.7</domain></domain-config>"""))
        assertEquals(emptyList(), cleartextTargets("""<domain-config cleartextTrafficPermitted="false"><domain>example.com</domain></domain-config>"""))
    }

    /** The hosts [xml] permits cleartext to; `everything` for a base-config or a manifest attribute, which name no host. */
    private fun cleartextTargets(xml: String): List<String> {
        val targets = mutableListOf<String>()
        for (tag in Regex("""<application\b[^>]*>""").findAll(xml)) if (setToAnythingButFalse(tag.value, "usesCleartextTraffic")) targets += "everything"
        for (tag in Regex("""<base-config\b[^>]*>""").findAll(xml)) if (setToAnythingButFalse(tag.value, "cleartextTrafficPermitted")) targets += "everything"
        for (config in Regex("""<domain-config\b([^>]*)>(.*?)</domain-config>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            if (!setToAnythingButFalse(config.groupValues[1], "cleartextTrafficPermitted")) continue
            targets += Regex("""<domain\b[^>]*>\s*([^<\s]+)\s*</domain>""").findAll(config.groupValues[2]).map { it.groupValues[1] }
        }
        return targets
    }

    private companion object {
        val LOOPBACK = setOf("127.0.0.1", "localhost")
    }
}
