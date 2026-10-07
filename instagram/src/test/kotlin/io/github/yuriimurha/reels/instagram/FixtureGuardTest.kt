package io.github.yuriimurha.reels.instagram

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Spec 10: fixtures are scrubbed. Fails if any fixture holds session material or a real Instagram CDN host. */
class FixtureGuardTest {
    private val forbidden = listOf(
        "session" + "id", "csrf" + "token", "Cookie" + ":", "ds_user" + "_id",
        "scontent", "fbcdn", "cdninstagram",
    )

    private fun offenders(dir: File): List<String> {
        return dir.walkTopDown().filter { it.isFile }.flatMap { file ->
            val text = file.readText()
            forbidden.filter { text.contains(it, ignoreCase = true) }.map { "${file.name}: $it" }
        }.toList()
    }

    @Test
    fun fixturesHoldNoSessionMaterialOrRealHosts() {
        val dir = File("src/test/resources/fixtures")
        assertTrue(dir.isDirectory, "unit tests must run from the instagram module directory")
        val violations = offenders(dir)
        if (violations.isNotEmpty()) fail("Unscrubbed fixtures:\n" + violations.joinToString("\n"))
    }

    @Test
    fun nestedPlantedValueIsDetected() {
        val tempDir = createTempDirectory().toFile()
        tempDir.deleteOnExit()
        val nested = tempDir.resolve("subdir")
        nested.mkdirs()

        val planted = "{\"" + "session" + "id" + "\": \"s1\"}"
        nested.resolve("planted.json").writeText(planted)

        val found = offenders(tempDir)
        assertEquals(1, found.size, "should find exactly one offender")
        assertTrue(found[0].contains("planted.json"), "should name the file")
        assertTrue(found[0].contains("sessionid"), "should name the forbidden word")
    }

    @Test
    fun cleanFileIsNotDetected() {
        val tempDir = createTempDirectory().toFile()
        tempDir.deleteOnExit()
        val nested = tempDir.resolve("subdir")
        nested.mkdirs()

        nested.resolve("clean.json").writeText("{\"name\": \"value\"}")

        val found = offenders(tempDir)
        assertEquals(0, found.size, "should not flag clean files")
    }
}
