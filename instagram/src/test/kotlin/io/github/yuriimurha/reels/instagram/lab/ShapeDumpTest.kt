package io.github.yuriimurha.reels.instagram.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShapeDumpTest {
    private val fixtureNames = listOf(
        "collection_page.json", "collections_list.json", "media_info.json",
        "saved_page_last.json", "saved_page_more.json", "saved_page_unavailable.json",
    )

    private fun fixture(name: String): JsonElement =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/web/$name")!!.readText())

    private fun dump(json: String): String = ShapeDump.of(Json.parseToJsonElement(json))

    private val savedMore get() = ShapeDump.of(fixture("saved_page_more.json"))

    @Test
    fun enumsAndFlagsAreShownWithTheirValue() {
        val shape = savedMore
        assertTrue("media_type number = 2" in shape, shape)
        assertTrue("product_type string = \"clips\"" in shape, shape)
        assertTrue("more_available boolean = true" in shape, shape)
        assertTrue("num_results number = 3" in shape, shape)
        assertTrue("original_width number = 1080" in shape, shape)
        assertTrue("status string = \"ok\"" in shape, shape)
    }

    @Test
    fun anIdShowsOnlyItsDigitCount() {
        val shape = savedMore
        assertTrue("pk number(19 digits)" in shape, shape)
        assertTrue("taken_at number(10 digits)" in shape, shape)
    }

    @Test
    fun aHandleShowsOnlyItsLengthAndCharacterClass() {
        val shape = savedMore
        assertTrue("username string(len 6, text)" in shape, shape)
        assertTrue("text string(len 21, text)" in shape, shape)
    }

    @Test
    fun aCandidateUrlShowsItsHostClassParameterNamesAndExpiry() {
        val shape = savedMore
        assertTrue("url url(host=other, params=[_nc_ht, oe, stp], oe=hex)" in shape, shape)
    }

    @Test
    fun anArrayShowsItsLengthAndExpandsOnlyElementZero() {
        val shape = savedMore
        assertTrue("items array[3]" in shape, shape)
        assertTrue("candidates array[3]" in shape, shape)
        assertEquals(1, shape.lines().count { it.trim() == "[0] object" && it.startsWith("  [0]") }, shape)
        assertTrue(shape.lines().none { it.trim().startsWith("[1]") }, shape)
    }

    @Test
    fun oneLinePerKeyIndentedTwoSpacesPerLevel() {
        assertEquals(
            listOf(
                "items array[3]",
                "  [0] object",
                "    media object",
                "      pk number(19 digits)",
                "      id string(len 22, base64url)",
                "      code string(len 11, text)",
            ),
            savedMore.lines().take(6),
        )
    }

    @Test
    fun noValueOfAnyFixtureSurvives() {
        val visible = LabRules.VISIBLE_VALUE_KEYS
        for (name in fixtureNames) {
            val shape = ShapeDump.of(fixture(name))
            // Every primitive that is not under a visible-value key must be absent from the dump.
            fun walk(key: String?, element: JsonElement) {
                when (element) {
                    is JsonObject -> element.forEach { (k, v) -> walk(k, v) }
                    is JsonArray -> element.forEach { walk(key, it) }
                    is JsonNull -> Unit
                    is JsonPrimitive -> if (key !in visible && element.content.length >= 4 && element.content != "true" && element.content != "false") {
                        assertFalse(element.content in shape, "$name leaks '${element.content}' under $key")
                    }
                }
            }
            walk(null, fixture(name))
            for (forbidden in listOf("3100000000000000001", "7700000001", "17900000000000002", "user_1", "cdn.example.invalid", "Synthetic", "QVFE")) {
                assertFalse(forbidden in shape, "$name shows $forbidden")
            }
        }
    }

    @Test
    fun aVisibleKeyShowsPlainMessagesButHidesAnythingThatCouldIdentify() {
        val plain = dump("""{"message":"Please wait a few minutes before you try again.","status":"fail"}""")
        assertTrue("message string = \"Please wait a few minutes before you try again.\"" in plain, plain)
        assertTrue("status string = \"fail\"" in plain, plain)

        val withId = dump("""{"message":"Media 3100000000000000001 is gone"}""")
        assertFalse("3100000000000000001" in withId, withId)
        assertTrue("message string(len 33, text)" in withId, withId)

        val withHandle = dump("""{"message":"Sorry, @someone is private"}""")
        assertFalse("someone" in withHandle, withHandle)

        val withUrl = dump("""{"message":"Go to https://other.example.invalid/x?y=1"}""")
        assertFalse("other.example.invalid" in withUrl, withUrl)

        val withHost = dump("""{"message":"Retry at www.example.invalid later"}""")
        assertFalse("example.invalid" in withHost, withHost)
    }

    @Test
    fun aVisibleKeyHoldingAForbiddenWordIsHiddenEvenInPlainText() {
        val shape = dump("""{"message":"Your ${"session" + "id"} expired","status":"fine"}""")
        assertTrue("message string(len 22, text)" in shape, shape)
        assertFalse(LabRules.hasForbiddenWord(shape), shape)
        assertTrue("status string = \"fine\"" in shape, shape)
    }

    @Test
    fun digitGroupsNamesAndHostsInAVisibleStringAreHiddenOnScreenToo() {
        val rows = listOf(
            "Item 3,100,000,000,000,000,001 gone", "pk 3100-0000-0000-0000-001", "user 3100 0000 0000 0000 001", "Code 1234",
            "John Doe", "john.doe", "Sorry, john.doe is private", "see evil.example.xyz now", "john.doe at gmail dot com",
        )
        for (row in rows) {
            val shape = ShapeDump.of(JsonObject(mapOf("message" to JsonPrimitive(row))))
            assertEquals("message string(len ${row.length}, text)", shape, row)
        }
    }

    private fun shownOnScreen(text: String): Boolean =
        ShapeDump.of(JsonObject(mapOf("message" to JsonPrimitive(text)))) == "message string = \"$text\""

    /** H6: an id written with sentence punctuation between its digits is still an id. The check strips the whole set first. */
    @Test
    fun aDigitRunSplitByColonsOrParenthesesIsHiddenOnScreenToo() {
        for (text in listOf("id 31:00:00:00:00", "(31)(00)(00)", "31:00:00:00", "pk 31;00;00;00", "id 31!00!00!00", "id 31?00?00?00", "id 31'00'00'00")) {
            assertFalse(LabRules.isVisibleString(text), text)
            assertEquals("message string(len ${text.length}, text)", ShapeDump.of(JsonObject(mapOf("message" to JsonPrimitive(text)))), text)
        }
    }

    @Test
    fun everyMarkOfTheSentenceSetCountsAsASeparatorBetweenDigits() {
        for (mark in " .,:;!?'\"()-_/") {
            val text = "id 31${mark}00${mark}00"
            assertFalse(LabRules.isVisibleString(text), "'$text': $mark between digits must not hide a run")
        }
    }

    @Test
    fun sentencesWithSmallNumbersStillShow() {
        for (text in listOf("Wait 2 minutes, 30 seconds", "Try again in 5 minutes.", "You can retry in 10 minutes or 2 hours!", "Please wait 20 (twenty) minutes")) {
            assertTrue(LabRules.isVisibleString(text), text)
            assertTrue(shownOnScreen(text), text)
        }
    }

    @Test
    fun anEnumLikeValueMayHaveAnyCase() {
        // KDoc: "one word of letters or underscores, any case" (the old text said bare lowercase). Keys are lowercase only.
        for (text in listOf("clips", "JohnDoe", "ALL_MEDIA_AUTO_COLLECTION", "Jane_Doe")) assertTrue(LabRules.isVisibleString(text), text)
        assertFalse(LabRules.isSafeName("JohnDoe"), "a KEY must be lowercase")
    }

    @Test
    fun anEnumLikeValueUnderAVisibleKeyIsShownEvenWhenItLooksLikeAHandle() {
        // Documented limit: one word of letters or underscores under an enum-like key cannot be told from an enum value.
        val shape = dump("""{"status":"johndoe","error_type":"jane_doe"}""")
        assertEquals("status string = \"johndoe\"\nerror_type string = \"jane_doe\"", shape)
    }

    @Test
    fun aVisibleNumberIsShownOnlyWithSixCharactersOrFewer() {
        val shape = dump("""{"width":123456,"height":1234567,"status":250253201}""")
        assertEquals("width number = 123456\nheight number(7 digits)\nstatus number(9 digits)", shape)
    }

    @Test
    fun aLongNumberUnderAVisibleKeyIsStillHidden() {
        val shape = dump("""{"width":1080,"height":3100000000000000001}""")
        assertTrue("width number = 1080" in shape, shape)
        assertTrue("height number(19 digits)" in shape, shape)
        assertFalse("3100000000000000001" in shape, shape)
    }

    @Test
    fun hostClassesAreInstagramCdnAndOtherAndTheHostIsNeverPrinted() {
        // Spelled in parts: the real CDN host names must not sit in the source as literals.
        val instagramCdn = "scontent-x." + "cdn" + "instagram.com"
        val facebookCdn = "scontent.xx." + "fb" + "cdn.net"
        val shape = dump(
            """{"a":"https://www.instagram.com/p/abc/","b":"https://instagram.com/","c":"https://$instagramCdn/v/t51/1.jpg?oe=6720A3F0",
            "d":"https://$facebookCdn/v/1.jpg","e":"https://evil.example.invalid/instagram.com",
            "f":"https://instagram.com.evil.example.invalid/","g":"http://WWW.Instagram.COM/x"}""",
        )
        val lines = shape.lines().associate { it.substringBefore(' ') to it.substringAfter(' ') }
        assertEquals("url(host=instagram, params=[], oe=absent)", lines["a"])
        assertEquals("url(host=instagram, params=[], oe=absent)", lines["b"])
        assertEquals("url(host=cdn, params=[oe], oe=hex)", lines["c"])
        assertEquals("url(host=cdn, params=[], oe=absent)", lines["d"])
        assertEquals("url(host=other, params=[], oe=absent)", lines["e"])
        assertEquals("url(host=other, params=[], oe=absent)", lines["f"])
        assertEquals("url(host=instagram, params=[], oe=absent)", lines["g"])
        assertFalse(LabRules.hasForbiddenWord(shape), shape)
        assertFalse("evil" in shape || "scontent" in shape || "instagram.com" in shape, shape)
    }

    @Test
    fun theExpiryParameterIsHexAbsentOrMalformed() {
        val shape = dump(
            """{"hex":"https://h.example.invalid/a?oe=6720a3f0","none":"https://h.example.invalid/a?x=1",
            "bad":"https://h.example.invalid/a?oe=tomorrow","empty":"https://h.example.invalid/a?oe","long":"https://h.example.invalid/a?oe=${"f".repeat(17)}"}""",
        )
        val lines = shape.lines().associate { it.substringBefore(' ') to it.substringAfter(' ') }
        assertEquals("url(host=other, params=[oe], oe=hex)", lines["hex"])
        assertEquals("url(host=other, params=[x], oe=absent)", lines["none"])
        assertEquals("url(host=other, params=[oe], oe=malformed)", lines["bad"])
        assertEquals("url(host=other, params=[oe], oe=malformed)", lines["empty"])
        assertEquals("url(host=other, params=[oe], oe=malformed)", lines["long"])
    }

    @Test
    fun stringsShowTheirLengthAndClass() {
        val shape = dump(
            """{"d":"17900000000000002","h":"6720A3F0","b":"QVFEabcdef_-12","t":"hello world","short":"user_1","relative":"/challenge/x/"}""",
        )
        val lines = shape.lines().associate { it.substringBefore(' ') to it.substringAfter(' ') }
        assertEquals("string(len 17, digits)", lines["d"])
        assertEquals("string(len 8, hex)", lines["h"])
        assertEquals("string(len 14, base64url)", lines["b"])
        assertEquals("string(len 11, text)", lines["t"])
        assertEquals("string(len 6, text)", lines["short"])
        assertEquals("string(len 13, text)", lines["relative"])
    }

    @Test
    fun booleansNullsAndEmptyContainers() {
        val shape = dump("""{"flag":true,"nothing":null,"obj":{},"arr":[],"spam":false}""")
        assertEquals(
            listOf("flag boolean = true", "nothing null", "obj object", "arr array[0]", "spam boolean = false"),
            shape.lines(),
        )
    }

    @Test
    fun aKeyThatIsDataIsRedactedLikeAValue() {
        val word = "session" + "id"
        val shape = dump("""{"17900000000000002":{"a":1},"john.doe":2,"$word":3,"${word}_x":4,"image_versions2":5}""")
        assertTrue("key(len 17, digits) object" in shape, shape)
        assertTrue("key(len 8, text) number(1 digits)" in shape, shape)
        assertTrue("image_versions2 number(1 digits)" in shape, shape)
        assertFalse(LabRules.hasForbiddenWord(shape), shape)
        assertFalse("17900000000000002" in shape || "john" in shape, shape)
    }

    @Test
    fun aParameterNameThatIsDataIsRedacted() {
        val word = "session" + "id"
        val shape = dump("""{"u":"https://h.example.invalid/a?$word=1&john.doe=2&stp=3"}""")
        assertEquals("u url(host=other, params=[?, ?, stp], oe=absent)", shape)
    }

    @Test
    fun aRootThatIsNotAnObjectStillDumps() {
        assertEquals(listOf("$ array[2]", "  [0] number(1 digits)"), dump("[1,2]").lines())
        assertEquals("$ string(len 2, text)", dump("\"hi\""))
        assertEquals("$ null", dump("null"))
    }

    @Test
    fun anArrayOfPrimitivesKeepsTheArraysKeyForItsRules() {
        val shape = dump("""{"saved_collection_ids":["17900000000000002","17900000000000003"],"message":["Try again later"]}""")
        assertTrue("  [0] string(len 17, digits)" in shape, shape)
        assertTrue("  [0] string = \"Try again later\"" in shape, shape)
    }
}
