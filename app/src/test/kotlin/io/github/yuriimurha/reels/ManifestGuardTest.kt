package io.github.yuriimurha.reels

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins manifest flags the UI depends on, read from the source manifest (in the style of [AppIconGuardTest]; it runs from the app
 * module directory).
 */
class ManifestGuardTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun manifest(): Element {
        val file = File("src/main/AndroidManifest.xml").also { assertTrue(it.isFile, "${it.path} is missing") }
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.elements(tag: String): List<Element> = getElementsByTagName(tag).let { list -> List(list.length) { list.item(it) as Element } }

    /**
     * Predictive back (spec 2026-10-12 §3.6): the app opts in, so the system shows the back gesture's preview and Navigation
     * seeks its pop transitions with it. An activity can opt itself back out (API 33+), so none may.
     */
    @Test
    fun predictiveBackIsOn() {
        val root = manifest()
        val application = root.elements("application").single()
        assertEquals("true", application.getAttributeNS(android, "enableOnBackInvokedCallback"))
        for (activity in root.elements("activity")) {
            val own = activity.getAttributeNS(android, "enableOnBackInvokedCallback")
            assertTrue(own.isEmpty() || own == "true", "${activity.getAttributeNS(android, "name")} turns predictive back off")
        }
    }
}
