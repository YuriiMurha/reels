package io.github.yuriimurha.reels.data.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FtsQueryTest {
    @Test
    fun wordsBecomePrefixTerms() = assertEquals("leg* day*", FtsQuery.from("Leg day"))

    @Test
    fun ftsOperatorsAreNeutralised() {
        assertEquals("or* cats*", FtsQuery.from("OR -cats"))
        assertEquals("hello* world*", FtsQuery.from("\"hello world\""))
        assertEquals("c*", FtsQuery.from("c++*"))
        assertEquals("near* not* and*", FtsQuery.from("NEAR NOT AND"))
    }

    @Test
    fun nonLatinLettersAreKept() = assertEquals("привет* café*", FtsQuery.from("Привет café"))

    @Test
    fun nothingSearchableGivesNull() {
        assertNull(FtsQuery.from(""))
        assertNull(FtsQuery.from("   "))
        assertNull(FtsQuery.from("\"'*-()"))
        assertNull(FtsQuery.from("🔥🔥"))
    }
}
