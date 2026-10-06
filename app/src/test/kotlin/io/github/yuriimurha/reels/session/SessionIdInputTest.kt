package io.github.yuriimurha.reels.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionIdInputTest {
    private val expected = SessionIdInput.Parsed(sessionId = "42%3Aab", userId = "42")

    @Test
    fun acceptsTheShapesPeopleCopy() {
        val inputs = listOf(
            "42%3Aab",
            "42:ab",
            "sessionid=42%3Aab",
            "Sessionid=42%3Aab; Path=/",
            "\"42%3Aab\"",
            "  42%3Aab ;  ",
            "'sessionid=42:ab'",
        )
        for (input in inputs) assertEquals(expected, SessionIdInput.parse(input), input)
    }

    @Test
    fun rejectsAnythingElse() {
        val inputs = listOf("", "   ", "hello", "%3Aab", "ab%3A42x", "42%3Aa b", "42 %3Aab", "42%3Aab,43%3Acd")
        for (input in inputs) assertNull(SessionIdInput.parse(input), input)
    }
}
