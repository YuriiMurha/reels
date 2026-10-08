package io.github.yuriimurha.reels.instagram.web

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one cookie parser. [SessionGuard] compares the session a request SENT (read from its Cookie header) with the one the jar
 * holds NOW (read through [cookieValue]); the comparison is only sound if both read a header exactly the same way. They
 * share [cookieValueIn], so they can't drift, and this pins what it does, byte for byte, including the odd cases.
 *
 * Built from parts: nothing here is a session, but nothing here should look like one either.
 */
class CookieValueInTest {
    private val sid = "session" + "id"

    private class Case(val what: String, val header: String, val name: String, val expected: String?)

    private val cases = listOf(
        Case("a plain pair", "$sid=s1", sid, "s1"),
        Case("the second of two", "a=1; $sid=s1", sid, "s1"),
        Case("the first of two with the same name", "$sid=s1; $sid=s2", sid, "s1"),
        Case("an empty first one wins over a later one: no fall through", "$sid=; $sid=s2", sid, null),
        Case("everything after the FIRST equals sign", "$sid=ab=cd==", sid, "ab=cd=="),
        Case("an equals sign right after the name's own", "$sid==s1", sid, "=s1"),
        Case("an empty value is null", "$sid=; a=1", sid, null),
        Case("an empty value at the end is null", "a=1; $sid=", sid, null),
        Case("a name that only ends with it is not a match", "x$sid=evil", sid, null),
        Case("a name that only starts with it is not a match", "${sid}2=evil", sid, null),
        Case("a name that only starts with it, next to the real one", "${sid}2=evil; $sid=s1", sid, "s1"),
        Case("a name with a prefix, next to the real one", "x$sid=evil; $sid=s1", sid, "s1"),
        Case("the name is case sensitive", "${sid.uppercase()}=s1", sid, null),
        Case("the name is case sensitive, the other way", "$sid=s1", sid.uppercase(), null),
        Case("pairs are trimmed", "  a=1 ;   $sid=s1  ; b=2", sid, "s1"),
        Case("tabs are trimmed too", "\t$sid=s1\t", sid, "s1"),
        Case("a space before the equals sign is not a match", "$sid =s1", sid, null),
        Case("a space after the equals sign is part of the value", "$sid= s1", sid, " s1"),
        Case("a space inside the value stays", "a=b c", "a", "b c"),
        Case("quotes are kept as they are", "rur=\"CLN\\0541234\"", "rur", "\"CLN\\0541234\""),
        Case("a quoted value with a space and a semicolon is cut at the semicolon", "a=\"x; y\"", "a", "\"x"),
        Case("no equals sign at all", sid, sid, null),
        Case("an empty header", "", sid, null),
        Case("only separators", ";;; ;", sid, null),
        Case("another cookie only", "a=1; b=2", sid, null),
    )

    @Test
    fun theHelperReadsEveryCaseExactly() {
        for (case in cases) {
            assertEquals(case.expected, cookieValueIn(case.header, case.name), case.what)
        }
    }

    /** A store whose jar holds exactly this header, whatever URL is asked. */
    private fun storeHolding(header: String) = object : CookieStore {
        override fun cookieHeader(url: String): String? = header.ifEmpty { null }
        override fun setCookie(url: String, setCookie: String) = Unit
        override fun flush() = Unit
        override fun clearAll() = Unit
    }

    @Test
    fun theStoresExtensionReadsEveryCaseTheSameWay() {
        for (case in cases) {
            assertEquals(case.expected, storeHolding(case.header).cookieValue("https://www.instagram.com/", case.name), case.what)
        }
    }

    @Test
    fun aStoreWithNoCookiesAtAllIsNull() {
        val empty = object : CookieStore {
            override fun cookieHeader(url: String): String? = null
            override fun setCookie(url: String, setCookie: String) = Unit
            override fun flush() = Unit
            override fun clearAll() = Unit
        }
        assertNull(empty.cookieValue("https://www.instagram.com/", sid))
    }

    // ---- one parser, not two copies ----

    private fun mainSource(name: String): String {
        val file = File("src/main/kotlin/io/github/yuriimurha/reels/instagram/web/$name")
        assertTrue(file.isFile, "unit tests must run from the instagram module directory, and $name must exist")
        // Comment lines (KDoc, //) are dropped: they may describe the parsing without doing it.
        return file.readLines().filterNot { it.trim().let { l -> l.startsWith("*") || l.startsWith("/*") || l.startsWith("//") } }.joinToString("\n")
    }

    @Test
    fun theSessionGuardAsksTheSharedParserInsteadOfParsingItself() {
        val code = mainSource("SessionGuard.kt")
        assertTrue("cookieValueIn(" in code, "SessionGuard must read the sent session with cookieValueIn")
        assertFalse(Regex("""split\s*\(\s*';'""").containsMatchIn(code), "SessionGuard must not split a Cookie header itself")
        assertFalse("startsWith(" in code, "SessionGuard must not match cookie names itself")
    }

    @Test
    fun theStoresExtensionAsksTheSharedParserToo() {
        val code = mainSource("CookieStore.kt")
        val extension = code.substringAfter("fun CookieStore.cookieValue(").substringBefore("\n\n")
        assertTrue("cookieValueIn(" in extension, "cookieValue must read the header with cookieValueIn: $extension")
        assertFalse(Regex("""split\s*\(\s*';'""").containsMatchIn(extension), "cookieValue must not split the header itself: $extension")
    }

    /** Anything else in the web package that matched `name=` against a split header would be a third reader. */
    @Test
    fun theHelperIsTheOnlyPlaceThatMatchesANameInACookieHeader() {
        val dir = File("src/main/kotlin/io/github/yuriimurha/reels/instagram/web")
        assertTrue(dir.isDirectory, "unit tests must run from the instagram module directory")
        val matchers = dir.listFiles { f -> f.name.endsWith(".kt") }!!
            .filter { Regex("""startsWith\("\$\w+="\)""").containsMatchIn(mainSource(it.name)) }
            .map { it.name }
        assertEquals(listOf("CookieStore.kt"), matchers, "only cookieValueIn does the name= matching")
    }
}
