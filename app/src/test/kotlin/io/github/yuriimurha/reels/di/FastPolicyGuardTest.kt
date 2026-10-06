package io.github.yuriimurha.reels.di

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 4.5: the fast pacing policy can only exist next to the fake client. */
class FastPolicyGuardTest {
    @Test
    fun fastPacingIsOnlyReferencedByTheFakeBackend() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "unit tests must run from the app module directory")
        val users = sources
            .filter { "PacingPolicy.Fast" in it.readText() }
            .map { it.invariantSeparatorsPath.substringAfter("src/main/kotlin/") }
        assertEquals(listOf("io/github/yuriimurha/reels/di/Backend.kt"), users)
    }
}
