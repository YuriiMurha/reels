package io.github.yuriimurha.reels.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            "42%3aab",
        )
        for (input in inputs) assertEquals(expected, SessionIdInput.parse(input), input)
    }

    @Test
    fun rejectsAnythingElse() {
        val inputs = listOf(
            "", "   ", "hello", "%3Aab", "ab%3A42x", "42%3Aa b", "42 %3Aab", "42%3Aab,43%3Acd",
            "42:", "42%3A", "42%3a", "42%3Aab\u0007", "42%3Aaé", "42%3Aab\u0000", "42%3Aa\u007fb",
            "42%3Aa\"b", "42%3Aa\\b", "４２%3Aab",
        )
        for (input in inputs) assertNull(SessionIdInput.parse(input), input)
    }

    @Test
    fun parsedNeverPrintsTheSessionId() {
        val text = expected.toString()
        assertEquals("Parsed(sessionId=██, userId=42)", text)
        assertFalse(text.contains("ab"), text)
    }
}
