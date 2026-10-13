package io.github.yuriimurha.reels

import io.github.yuriimurha.reels.testutil.KotlinSource
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the app's own launcher icon and the viewer's three icon-button glyphs (spec 2026-10-12 §3.4 and §3.5), read from the source
 * files (in the style of [CleartextGuardTest]; they run from the app module directory).
 *
 * The icon is adaptive: the launcher masks it to a circle, a squircle or the like and may scale it, so only the inner 66 of the
 * foreground's 108 units are guaranteed to show (the safe zone, 21..87). A path that strays outside could be cut off.
 */
class AppIconGuardTest {
    private val main = File("src/main")
    private val android = "http://schemas.android.com/apk/res/android"

    /** An axis-aligned box in the 108-unit viewport. */
    private data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val centreX get() = (left + right) / 2
        val centreY get() = (top + bottom) / 2

        fun isInside(other: Box) = left >= other.left && top >= other.top && right <= other.right && bottom <= other.bottom

        fun matches(other: Box) = listOf(left - other.left, top - other.top, right - other.right, bottom - other.bottom).all { abs(it) < 1e-6 }

        override fun toString() = "($left, $top)..($right, $bottom)"
    }

    /** One `<path>`: its fill colour, its fill rule, and the box of each of its subpaths (each `M` starts one). */
    private data class Shape(val fill: String, val fillType: String, val boxes: List<Box>)

    private fun file(path: String) = File(main, path).also { assertTrue(it.isFile, "${it.path} is missing") }

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file(path)).documentElement

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    private fun Element.child(tag: String): Element {
        val found = getElementsByTagName(tag)
        assertEquals(1, found.length, "<$tag> in ${tagName}")
        return found.item(0) as Element
    }

    /**
     * The boxes of the subpaths of [pathData], from its endpoints. Only `M m L l H h V v A a Z z` are understood, and an arc
     * must be a quarter circle (the corner of a rounded rectangle): its bulge then stays inside the box of its endpoints, so
     * the endpoints bound the shape. Anything else fails, so no unmeasured geometry slips past the safe-zone check.
     */
    private fun subpathBoxes(pathData: String): List<Box> {
        val tokens = Regex("""[MmLlHhVvAaZz]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""").findAll(pathData).map { it.value }.toList()
        val boxes = mutableListOf<Box>()
        var x = 0.0
        var y = 0.0
        var startX = 0.0
        var startY = 0.0
        var minX = 0.0
        var minY = 0.0
        var maxX = 0.0
        var maxY = 0.0
        var open = false

        fun visit() {
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }

        fun finish() {
            if (open) boxes += Box(minX, minY, maxX, maxY)
            open = false
        }

        var i = 0
        var command = ' '
        fun number() = tokens[i++].toDouble()
        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) {
                command = tokens[i++][0]
            } else {
                // Numbers after a command repeat it; after a moveto they are linetos.
                command = when (command) { 'M' -> 'L'; 'm' -> 'l'; else -> command }
            }
            when (command) {
                'M', 'm' -> {
                    finish()
                    val dx = number(); val dy = number()
                    if (command == 'M') { x = dx; y = dy } else { x += dx; y += dy }
                    startX = x; startY = y
                    minX = x; maxX = x; minY = y; maxY = y
                    open = true
                }
                'L' -> { x = number(); y = number(); visit() }
                'l' -> { x += number(); y += number(); visit() }
                'H' -> { x = number(); visit() }
                'h' -> { x += number(); visit() }
                'V' -> { y = number(); visit() }
                'v' -> { y += number(); visit() }
                'A', 'a' -> {
                    val rx = number(); val ry = number(); number(); number(); number()
                    val ex = number(); val ey = number()
                    val dx = if (command == 'A') ex - x else ex
                    val dy = if (command == 'A') ey - y else ey
                    assertTrue(rx == ry && abs(abs(dx) - rx) < 1e-6 && abs(abs(dy) - ry) < 1e-6, "only quarter-circle arcs are measured: '$pathData'")
                    x += dx; y += dy; visit()
                }
                'Z', 'z' -> { x = startX; y = startY; visit(); finish() }
                else -> error("unexpected '$command' in '$pathData'")
            }
        }
        finish()
        return boxes
    }

    private fun shapes(drawable: String): List<Shape> {
        val paths = xml(drawable).getElementsByTagName("path")
        return (0 until paths.length).map { n ->
            val path = paths.item(n) as Element
            Shape(path.attr("fillColor").uppercase(), path.attr("fillType").ifEmpty { "nonZero" }, subpathBoxes(path.attr("pathData")))
        }
    }

    private val safeZone = Box(21.0, 21.0, 87.0, 87.0)

    /** Left column 30..52 and right column 56..78: a tall tile and a short one in each, staggered. */
    private val tallLeft = Box(30.0, 30.0, 52.0, 62.0)
    private val shortLeft = Box(30.0, 66.0, 52.0, 78.0)
    private val shortRight = Box(56.0, 30.0, 78.0, 44.0)
    private val tallRight = Box(56.0, 48.0, 78.0, 78.0)

    @Test
    fun theManifestNamesTheLauncherIcons() {
        val manifest = xml("AndroidManifest.xml")
        val application = manifest.child("application")
        assertEquals("@mipmap/ic_launcher", application.attr("icon"))
        assertEquals("@mipmap/ic_launcher_round", application.attr("roundIcon"))
        // T9: the launcher shows an activity's own icon over the app's, so none may name one.
        for (tag in listOf("activity", "activity-alias")) {
            val elements = manifest.getElementsByTagName(tag)
            for (element in (0 until elements.length).map { elements.item(it) as Element }) {
                for (name in listOf("icon", "roundIcon")) {
                    assertEquals("", element.attr(name), "<$tag ${element.attr("name")}> sets its own android:$name")
                }
            }
        }
    }

    @Test
    fun bothAdaptiveIconsHaveABackgroundAForegroundAndAMonochromeLayer() {
        for (icon in listOf("res/mipmap-anydpi-v26/ic_launcher.xml", "res/mipmap-anydpi-v26/ic_launcher_round.xml")) {
            val root = xml(icon)
            assertEquals("adaptive-icon", root.tagName, icon)
            assertEquals("@color/ic_launcher_background", root.child("background").attr("drawable"), icon)
            assertEquals("@drawable/ic_launcher_foreground", root.child("foreground").attr("drawable"), icon)
            assertEquals("@drawable/ic_launcher_monochrome", root.child("monochrome").attr("drawable"), icon)
        }
    }

    @Test
    fun theBackgroundIsTheTealOfTheTiles() {
        val colours = xml("res/values/ic_launcher_background.xml").getElementsByTagName("color")
        assertEquals(1, colours.length)
        val colour = colours.item(0) as Element
        assertEquals("ic_launcher_background", colour.getAttribute("name"))
        assertEquals("#0F6E56", colour.textContent.trim().uppercase())
    }

    @Test
    fun bothLayersDrawOnA108By108Viewport() {
        for (layer in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val vector = xml("res/drawable/$layer.xml")
            assertEquals("vector", vector.tagName, layer)
            assertEquals("108", vector.attr("viewportWidth"), layer)
            assertEquals("108", vector.attr("viewportHeight"), layer)
            assertEquals("108dp", vector.attr("width"), layer)
            assertEquals("108dp", vector.attr("height"), layer)
        }
    }

    /** Four rounded tiles in a masonry layout, then the play triangle on the bottom right one. */
    @Test
    fun theForegroundIsFourMasonryTilesAndAPlayTriangle() {
        val foreground = shapes("res/drawable/ic_launcher_foreground.xml")

        assertEquals(
            listOf("#9FE1CB", "#5DCAA5", "#5DCAA5", "#E1F5EE", "#0F6E56"),
            foreground.map { it.fill },
            "fills: the four tiles in reading order, then the triangle",
        )
        assertTrue(foreground.all { it.boxes.size == 1 }, "one shape a path: $foreground")
        val boxes = foreground.map { it.boxes.single() }
        for ((box, tile) in boxes.zip(listOf(tallLeft, shortLeft, shortRight, tallRight))) {
            assertTrue(box.matches(tile), "tile $tile drawn as $box")
        }
    }

    @Test
    fun thePlayTriangleIsCentredInTheBottomRightTile() {
        val triangle = shapes("res/drawable/ic_launcher_foreground.xml").last().boxes.single()

        assertTrue(triangle.isInside(tallRight), "the triangle $triangle lies inside the tile $tallRight")
        assertTrue(abs(triangle.centreX - tallRight.centreX) <= 1.5 && abs(triangle.centreY - tallRight.centreY) <= 1.5, "centre of $triangle vs $tallRight")
        assertTrue(triangle.right - triangle.left in 8.0..16.0, "the triangle is a recognisable size: $triangle")
    }

    @Test
    fun everyPathOfBothLayersStaysInsideTheSafeZone() {
        for (layer in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val shapes = shapes("res/drawable/$layer.xml")
            assertTrue(shapes.isNotEmpty(), layer)
            for (box in shapes.flatMap { it.boxes }) assertTrue(box.isInside(safeZone), "$layer: $box sticks out of the safe zone $safeZone")
        }
    }

    @Test
    fun theForegroundHasNoGroupThatCouldMoveAPathOutOfTheMeasuredBounds() {
        for (layer in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            assertEquals(0, xml("res/drawable/$layer.xml").getElementsByTagName("group").length, "$layer: the bounds are measured on the raw path data")
        }
    }

    /** Task 5 minor 1: a stroke is drawn half outside a path's edge, beyond the measured bounds, so no tile may have one. */
    @Test
    fun noPathOfEitherLayerHasAStroke() {
        val strokes = listOf("strokeColor", "strokeWidth", "strokeAlpha", "strokeLineCap", "strokeLineJoin", "strokeMiterLimit")
        for (layer in listOf("ic_launcher_foreground", "ic_launcher_monochrome")) {
            val paths = xml("res/drawable/$layer.xml").getElementsByTagName("path")
            for (path in (0 until paths.length).map { paths.item(it) as Element }) {
                for (stroke in strokes) assertEquals("", path.attr(stroke), "$layer: a path with android:$stroke")
            }
        }
    }

    /** A themed icon is tinted by the alpha of this layer alone: one colour, and the triangle a hole in its tile so it still shows. */
    @Test
    fun theMonochromeLayerIsTheSameShapesInOneColourWithTheTriangleCutOut() {
        val foreground = shapes("res/drawable/ic_launcher_foreground.xml")
        val monochrome = shapes("res/drawable/ic_launcher_monochrome.xml")

        assertTrue(monochrome.all { it.fill == "#FFFFFF" }, "one colour: ${monochrome.map { it.fill }}")
        assertEquals(4, monochrome.size, "the four tiles; the triangle is a hole in the last, not a path of its own")
        for ((mono, tile) in monochrome.zip(listOf(tallLeft, shortLeft, shortRight))) {
            assertEquals(1, mono.boxes.size)
            assertTrue(mono.boxes.single().matches(tile), "tile $tile drawn as ${mono.boxes}")
        }
        val last = monochrome.last()
        assertEquals("evenOdd", last.fillType, "an even-odd fill makes the inner subpath a hole")
        assertEquals(2, last.boxes.size, "the bottom right tile, then the triangle")
        assertTrue(last.boxes[0].matches(tallRight), "tile $tallRight drawn as ${last.boxes[0]}")
        assertTrue(last.boxes[1].matches(foreground.last().boxes.single()), "the same triangle as the foreground's: ${last.boxes[1]}")
    }

    @Test
    fun theViewerIconGlyphsAreMaterialSymbolsVectorsOf24Dp() {
        for (glyph in listOf("ic_volume_up", "ic_volume_off", "ic_open_in_new")) {
            val vector = xml("res/drawable/$glyph.xml")
            assertEquals("vector", vector.tagName, glyph)
            assertEquals("24dp", vector.attr("width"), glyph)
            assertEquals("24dp", vector.attr("height"), glyph)
            // Material Symbols are drawn on a 960 grid.
            assertEquals("960", vector.attr("viewportWidth"), glyph)
            assertEquals("960", vector.attr("viewportHeight"), glyph)
            // Compose tints the icon; a tint here would fight it.
            assertEquals("", vector.attr("tint"), glyph)
            assertTrue(vector.getElementsByTagName("path").length >= 1, glyph)
        }
    }

    /**
     * T10: each glyph is its own Material Symbols shape, not just its own file name. Each is pinned by where its outline starts
     * (`volume_off` begins with the slash across the speaker), and no two draw the same: with `volume_up` drawn as `volume_off`,
     * Mute and Unmute would look alike and the button would no longer show the state.
     */
    @Test
    fun eachViewerGlyphDrawsItsOwnShape() {
        val starts = mapOf("ic_volume_off" to "M792,904 ", "ic_volume_up" to "M560,829v", "ic_open_in_new" to "M200,840q")
        val paths = starts.keys.associateWith { glyph ->
            val path = xml("res/drawable/$glyph.xml").getElementsByTagName("path")
            (0 until path.length).joinToString("|") { (path.item(it) as Element).attr("pathData") }
        }
        for ((glyph, start) in starts) assertTrue(paths.getValue(glyph).startsWith(start), "$glyph starts '$start': ${paths.getValue(glyph).take(20)}")
        assertEquals(starts.size, paths.values.toSet().size, "no two glyphs draw the same path")
        assertFalse(paths.getValue("ic_volume_off") in paths.getValue("ic_volume_up"), "volume_up must not contain volume_off's path")
        assertFalse(paths.getValue("ic_volume_up") in paths.getValue("ic_volume_off"), "volume_off must not contain volume_up's path")
    }

    /** Compose semantics do not say which drawable an icon shows, so the pairing is pinned in the source: each label with its own glyph. */
    @Test
    fun theOverlayPairsEachLabelWithItsGlyph() {
        val source = file("kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerScreen.kt").readText()
        val calls = KotlinSource.callArguments(source, "OverlayIconButton(").filter { it.trimStart().startsWith("\"") }
        val pairs = calls.map { args -> args.split(",").let { it[0].trim().removeSurrounding("\"") to it[1].trim() } }

        assertEquals(
            setOf("Open on Instagram" to "R.drawable.ic_open_in_new", "Unmute" to "R.drawable.ic_volume_off", "Mute" to "R.drawable.ic_volume_up"),
            pairs.toSet(),
            "the overlay's icon buttons, by label",
        )
        assertEquals(3, pairs.size, "one call each: $pairs")

        // Task 5 minor 2: and the helper draws the glyph it is given, not one of its own.
        val start = source.indexOf("private fun OverlayIconButton(")
        assertTrue(start >= 0, "OverlayIconButton is missing")
        val body = source.substring(start, source.indexOf("\n}\n", start))
        val icons = KotlinSource.callArguments(body, "Icon(")
        assertEquals(1, icons.size, "one Icon in OverlayIconButton: $icons")
        assertTrue(icons.single().trimStart().startsWith("painterResource(icon)"), "the Icon draws the given glyph: Icon(${icons.single()})")
    }

    @Test
    fun theGlyphsCarryTheirApacheNotice() {
        val notice = file("res/raw/license_material_symbols.txt").readText()
        assertTrue("Material Symbols" in notice)
        assertTrue("Apache License" in notice && "Version 2.0" in notice)
        for (glyph in listOf("volume_up", "volume_off", "open_in_new")) assertTrue(glyph in notice, "the notice names $glyph")
        // Task 5 minor 3: the licence itself, not only its header: its terms from their title to their end.
        for (line in listOf("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", "Grant of Copyright License", "END OF TERMS AND CONDITIONS")) {
            assertTrue(line in notice, "the notice carries the licence's '$line'")
        }
    }

    /** No code reads the notice, so a resource-shrinking build would drop it: `res/raw/keep.xml` keeps it (and lint then calls it used). */
    @Test
    fun theNoticeIsKeptByResourceShrinking() {
        val keep = xml("res/raw/keep.xml")
        assertEquals("resources", keep.tagName)
        val kept = keep.getAttributeNS("http://schemas.android.com/tools", "keep").split(",").map { it.trim() }
        assertTrue("@raw/license_material_symbols" in kept, "tools:keep: $kept")
    }

    /** The glyphs are three small vector files of ours, not the `material-icons-extended` library (global constraints, icons). */
    @Test
    fun theExtendedIconLibraryIsNotADependency() {
        for (build in listOf(File("build.gradle.kts"), File("../gradle/libs.versions.toml"))) {
            assertTrue(build.isFile, "${build.path} is missing")
            assertFalse("material-icons-extended" in build.readText(), "${build.path} pulls in material-icons-extended")
        }
    }
}
