package io.github.yuriimurha.reels.instagram

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Spec 10: fixtures are scrubbed. Fails if any fixture holds session material or a real Instagram CDN host. */
class FixtureGuardTest {
    private val forbidden = listOf(
        "session" + "id", "csrf" + "token", "Cookie" + ":", "ds_user" + "_id",
        "scontent", "fbcdn", "cdninstagram",
    )

    @Test
    fun fixturesHoldNoSessionMaterialOrRealHosts() {
        val dir = File("src/test/resources/fixtures")
        assertTrue(dir.isDirectory, "unit tests must run from the instagram module directory")
        val offenders = dir.walkTopDown().filter { it.isFile }.flatMap { file ->
            val text = file.readText()
            forbidden.filter { text.contains(it, ignoreCase = true) }.map { "${file.name}: $it" }
        }.toList()
        if (offenders.isNotEmpty()) fail("Unscrubbed fixtures:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun theGuardCatchesAPlantedValue() {
        val planted = "{\"" + "session" + "id" + "\": \"s1\"}"
        assertTrue(forbidden.any { planted.contains(it, ignoreCase = true) })
    }
}
