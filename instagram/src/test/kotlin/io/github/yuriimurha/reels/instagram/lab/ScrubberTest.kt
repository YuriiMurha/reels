package io.github.yuriimurha.reels.instagram.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScrubberTest {
    private val fixtureNames = listOf(
        "collection_page.json", "collections_list.json", "media_info.json",
        "saved_page_last.json", "saved_page_more.json", "saved_page_unavailable.json",
    )

    // The word list of FixtureGuardTest, built from parts like there.
    private val forbidden = listOf(
        "session" + "id", "csrf" + "token", "Cookie" + ":", "ds_user" + "_id", "scontent", "fb" + "cdn", "cdn" + "instagram",
    )

    private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun fixture(name: String): JsonElement = parse(javaClass.getResource("/fixtures/web/$name")!!.readText())

    private fun render(element: JsonElement): String = Json.encodeToString(JsonElement.serializer(), element)

    /** Keys, array sizes and primitive kinds: the structure a scrub must keep. */
    private fun structure(element: JsonElement): Any = when (element) {
        is JsonObject -> element.mapValues { structure(it.value) }
        is JsonArray -> element.map(::structure)
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            element.isString -> "string"
            element.content == "true" || element.content == "false" -> "boolean"
            else -> "number"
        }
    }

    /** Every value stored under [key], in document order, at any depth. */
    private fun valuesOf(element: JsonElement, key: String): List<String> = when (element) {
        is JsonObject -> element.flatMap { (k, v) ->
            if (k == key && v is JsonPrimitive && v !is JsonNull) listOf(v.content) + valuesOf(v, key) else valuesOf(v, key)
        }
        is JsonArray -> element.flatMap { valuesOf(it, key) }
        else -> emptyList()
    }

    private fun allPrimitives(element: JsonElement, key: String? = null): List<Pair<String?, JsonPrimitive>> = when (element) {
        is JsonObject -> element.flatMap { (k, v) -> allPrimitives(v, k) }
        is JsonArray -> element.flatMap { allPrimitives(it, key) }
        is JsonNull -> emptyList()
        is JsonPrimitive -> listOf(key to element)
    }

    @Test
    fun structureAndKeysAreKept() {
        for (name in fixtureNames) {
            val original = fixture(name)
            assertEquals(structure(original), structure(Scrubber().scrub(original)), name)
        }
    }

    @Test
    fun idsKeepTheirDigitCountButChangeValue() {
        val original = fixture("saved_page_more.json")
        val scrubbed = Scrubber().scrub(original)
        val before = valuesOf(original, "pk")
        val after = valuesOf(scrubbed, "pk")
        assertEquals(before.size, after.size)
        assertTrue(before.isNotEmpty())
        before.zip(after).forEach { (b, a) ->
            assertEquals(b.length, a.length, "digit count of $b")
            assertNotEquals(b, a)
            assertTrue(a.all { it in '0'..'9' }, a)
        }
        // Distinct real ids stay distinct.
        assertEquals(before.distinct().size, after.distinct().size)
        // A number stays a number: the id was not turned into a string.
        val firstPk = scrubbed.jsonObject["items"]!!.jsonArray[0].jsonObject["media"]!!.jsonObject["pk"]!!.jsonPrimitive
        assertFalse(firstPk.isString)
    }

    @Test
    fun theSameIdMapsToTheSameSyntheticIdAcrossResponses() {
        val scrubber = Scrubber()
        val collections = scrubber.scrub(fixture("collections_list.json"))
        val saved = scrubber.scrub(fixture("saved_page_more.json"))

        // Food's cover_media.pk is the reel's pk (31...01).
        val foodCover = collections.jsonObject["items"]!!.jsonArray[1].jsonObject["cover_media"]!!.jsonObject
        val reel = saved.jsonObject["items"]!!.jsonArray[0].jsonObject["media"]!!.jsonObject
        assertEquals(foodCover["pk"]!!.jsonPrimitive.content, reel["pk"]!!.jsonPrimitive.content)

        // "<media pk>_<owner pk>" maps each part, so the id string cross-references the pk too.
        val pk = reel["pk"]!!.jsonPrimitive.content
        val id = reel["id"]!!.jsonPrimitive.content
        assertTrue(id.startsWith(pk + "_"), "$id vs $pk")
        assertEquals(foodCover["id"]!!.jsonPrimitive.content, id)
        assertEquals(22, id.length, "id keeps its length")

        // The collection id on the saved item is Food's collection_id.
        val foodId = collections.jsonObject["items"]!!.jsonArray[1].jsonObject["collection_id"]!!.jsonPrimitive.content
        val listed = reel["saved_collection_ids"]!!.jsonArray[0].jsonPrimitive.content
        assertEquals(foodId, listed)
        assertNotEquals("17900000000000002", foodId)
        assertEquals(17, foodId.length)
    }

    @Test
    fun aFreshScrubberStartsItsMappingOver() {
        val a = render(Scrubber().scrub(fixture("saved_page_more.json")))
        val b = render(Scrubber().scrub(fixture("saved_page_more.json")))
        assertEquals(a, b)
    }

    @Test
    fun theExpiryParameterIsKeptAndEveryOtherParameterValueIsX() {
        val scrubbed = Scrubber().scrub(fixture("saved_page_more.json"))
        val urls = valuesOf(scrubbed, "url")
        assertTrue(urls.isNotEmpty())
        urls.forEach { assertTrue(it.matches(Regex("https://cdn\\.example\\.invalid/m/[0-9]+\\?stp=x&oe=6720A3F0&_nc_ht=x")), it) }
        assertEquals(urls.size, urls.distinct().size, "distinct links stay distinct")
        assertEquals("https://cdn.example.invalid/m/1?stp=x&oe=6720A3F0&_nc_ht=x", urls.first())
    }

    @Test
    fun aMalformedExpiryAndDuplicateOrOddParametersAreScrubbed() {
        val scrubbed = Scrubber().scrub(
            parse("""{"a":"https://h.example.invalid/p?oe=tomorrow&k=1&k=2&john.doe=3","b":"https://h.example.invalid/q","c":"https://"}"""),
        ).jsonObject
        assertEquals("https://cdn.example.invalid/m/1?oe=x&k=x&k=x&p3=x", scrubbed["a"]!!.jsonPrimitive.content)
        assertEquals("https://cdn.example.invalid/m/2", scrubbed["b"]!!.jsonPrimitive.content)
        // Not a parseable URL: an ordinary string.
        assertEquals("s_1", scrubbed["c"]!!.jsonPrimitive.content)
    }

    @Test
    fun valuesUnderVisibleKeysStay() {
        val scrubbed = Scrubber().scrub(fixture("saved_page_more.json"))
        val media = scrubbed.jsonObject["items"]!!.jsonArray[0].jsonObject["media"]!!.jsonObject
        assertEquals("2", media["media_type"]!!.jsonPrimitive.content)
        assertEquals("clips", media["product_type"]!!.jsonPrimitive.content)
        assertEquals("1080", media["original_width"]!!.jsonPrimitive.content)
        assertEquals("1920", media["original_height"]!!.jsonPrimitive.content)
        assertEquals("true", scrubbed.jsonObject["more_available"]!!.jsonPrimitive.content)
        assertEquals("3", scrubbed.jsonObject["num_results"]!!.jsonPrimitive.content)
        assertEquals("ok", scrubbed.jsonObject["status"]!!.jsonPrimitive.content)
        val collections = Scrubber().scrub(fixture("collections_list.json")).jsonObject["items"]!!.jsonArray
        assertEquals(
            listOf("ALL_MEDIA_AUTO_COLLECTION", "MEDIA", "MEDIA", "AUDIO_AUTO_COLLECTION"),
            collections.map { it.jsonObject["collection_type"]!!.jsonPrimitive.content },
        )
        val carousel = scrubbed.jsonObject["items"]!!.jsonArray[2].jsonObject["media"]!!.jsonObject
        assertEquals("8", carousel["media_type"]!!.jsonPrimitive.content)
        assertEquals("3", carousel["carousel_media_count"]!!.jsonPrimitive.content)
    }

    @Test
    fun handlesCaptionsShortcodesTimestampsAndOtherValuesGetSyntheticStandIns() {
        val scrubbed = Scrubber().scrub(
            parse(
                """{"username":"alice","full_name":"Alice A","owner":{"username":"alice"},"text":"hello there","code":"Cx1aBcDeFg1",
                "taken_at":1700003000,"device_timestamp":99,"other_at":5,"count":12345,"ratio":0.5,"note":"abc",
                "flag":true,"nothing":null}""",
            ),
        )
        assertEquals(
            parse(
                """{"username":"user_1","full_name":"user_2","owner":{"username":"user_1"},"text":"caption 1","code":"C0000000001",
                "taken_at":1700086400,"device_timestamp":1700172800,"other_at":1700259200,"count":1,"ratio":2,"note":"s_1",
                "flag":true,"nothing":null}""",
            ),
            scrubbed,
        )
    }

    @Test
    fun aShortcodeKeepsItsLengthOrGrowsToFitItsNumber() {
        val scrubbed = Scrubber().scrub(parse("""{"a":{"code":"C"},"b":{"code":""},"c":{"code":"Cx1aBcDeFg1"},"d":{"code":"Cx1aBcDeFg2"}}""")).jsonObject
        assertEquals("C1", scrubbed["a"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("C2", scrubbed["b"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("C0000000003", scrubbed["c"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals("C0000000004", scrubbed["d"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun everyAllDigitStringIsAnIdWhateverItsKey() {
        val scrubbed = Scrubber().scrub(parse("""{"count_text":"12345","again":"12345","pair":"12_345678","other":"54321"}""")).jsonObject
        val a = scrubbed["count_text"]!!.jsonPrimitive
        assertTrue(a.isString)
        assertEquals(5, a.content.length)
        assertNotEquals("12345", a.content)
        assertEquals(a.content, scrubbed["again"]!!.jsonPrimitive.content)
        assertNotEquals(a.content, scrubbed["other"]!!.jsonPrimitive.content)
        val (left, right) = scrubbed["pair"]!!.jsonPrimitive.content.split('_')
        assertEquals(2, left.length)
        assertEquals(6, right.length)
        assertNotEquals("12", left)
    }

    @Test
    fun idKeysCoverPkIdFbidAndTheIdSuffixes() {
        val scrubbed = Scrubber().scrub(
            parse("""{"pk":1234567,"id":7654321,"fbid":1111111,"user_id":2222222,"strong_id":"3333333","saved_collection_ids":[4444444,"5555555"],"counter":1234567}"""),
        ).jsonObject
        for (key in listOf("pk", "id", "fbid", "user_id", "strong_id")) {
            val value = scrubbed[key]!!.jsonPrimitive.content
            assertEquals(7, value.length, key)
            assertTrue(value.all { it in '0'..'9' }, key)
        }
        assertEquals(
            listOf(7, 7),
            scrubbed["saved_collection_ids"]!!.jsonArray.map { it.jsonPrimitive.content.length },
        )
        // Same real number, different key: only an id key keeps the digit count.
        assertEquals("1", scrubbed["counter"]!!.jsonPrimitive.content)
        assertNotEquals(scrubbed["pk"]!!.jsonPrimitive.content, scrubbed["counter"]!!.jsonPrimitive.content)
    }

    @Test
    fun shortAndVeryLongIdsStillDifferFromTheOriginalAndKeepTheirLength() {
        val long = "1234567890123456789012345"
        val scrubbed = Scrubber().scrub(parse("""{"pk":1,"id":2,"fbid":10,"user_id":$long}""")).jsonObject
        assertEquals("2", scrubbed["pk"]!!.jsonPrimitive.content)
        assertEquals("3", scrubbed["id"]!!.jsonPrimitive.content)
        assertNotEquals("10", scrubbed["fbid"]!!.jsonPrimitive.content)
        assertEquals(2, scrubbed["fbid"]!!.jsonPrimitive.content.length)
        val big = scrubbed["user_id"]!!.jsonPrimitive
        assertEquals(25, big.content.length)
        assertFalse(big.isString)
        assertNotEquals(long, big.content)
        // The first synthetic id of its length would equal this real one, so it is skipped.
        val clash = Scrubber().scrub(parse("""{"x_id":"1000000000000000000000001"}""")).jsonObject
        assertEquals("1000000000000000000000002", clash["x_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aKeyThatIsDataIsMappedLikeAValue() {
        val scrubbed = Scrubber().scrub(
            parse("""{"17900000000000002":{"a":1},"collection_id":"17900000000000002","john.doe":{"b":2},"x":{"john.doe":3}}"""),
        ).jsonObject
        val idKey = scrubbed.keys.first()
        assertEquals(17, idKey.length)
        assertNotEquals("17900000000000002", idKey)
        assertEquals(idKey, scrubbed["collection_id"]!!.jsonPrimitive.content)
        assertEquals(listOf(idKey, "collection_id", "key_1", "x"), scrubbed.keys.toList())
        assertEquals(listOf("key_1"), scrubbed["x"]!!.jsonObject.keys.toList())
    }

    @Test
    fun theOutputPassesTheFixtureGuardWordList() {
        for (name in fixtureNames) {
            assertNoForbiddenWord(render(Scrubber().scrub(fixture(name))), name)
        }
    }

    @Test
    fun aPlantedInputStillPassesTheFixtureGuardWordList() {
        val instagramCdn = "scontent-x." + "cdn" + "instagram.com"
        val facebookCdn = "instagram.fab1-1." + "fb" + "cdn.net"
        val sessionKey = "session" + "id"
        val userKey = "ds_user" + "_id"
        val csrf = "csrf" + "token"
        // A header-shaped value, built from parts so the secret guard never sees a literal.
        val cookieHeader = "Cookie" + ": a=b"
        val planted = """
            {
              "url": "https://$instagramCdn/v/t51/1.jpg?oe=6720A3F0&_nc_sid=abc&stp=dst-jpg",
              "thumb": "https://$facebookCdn/v/t51/2.jpg?$userKey=42&$sessionKey=s1",
              "$userKey": "42",
              "${sessionKey}_note": "s1",
              "$csrf": "abc",
              "message": "Open $instagramCdn first",
              "feedback_title": "See $facebookCdn",
              "status": "ok",
              "title": "$cookieHeader",
              "note": "$csrf and $sessionKey",
              "text": "https://$instagramCdn/caption",
              "username": "$instagramCdn",
              "nested": [{"https://$instagramCdn/key": 1}, "scontent", "$instagramCdn"]
            }
        """.trimIndent()
        val out = render(Scrubber().scrub(parse(planted)))
        assertNoForbiddenWord(out, "planted")
        // The planted input really does hold every word: the check above is not vacuous.
        for (word in forbidden) assertTrue(planted.contains(word, ignoreCase = true), word)
    }

    @Test
    fun noOriginalValueSurvivesInAnyFixture() {
        for (name in fixtureNames) {
            val original = fixture(name)
            val out = render(Scrubber().scrub(original))
            for ((key, primitive) in allPrimitives(original)) {
                val text = primitive.content
                // The fixtures' own handles are already user_<n>, which is also what a scrub writes, so they coincide.
                if (key in LabRules.VISIBLE_VALUE_KEYS || text.length < 5 || text.startsWith("user_") || text == "false") continue
                assertFalse(text in out, "$name still holds '$text' under $key")
            }
        }
    }

    @Test
    fun aVisibleKeyWithIdentifyingTextIsStillScrubbed() {
        val scrubbed = Scrubber().scrub(
            parse("""{"message":"Hi @someone, media 3100000000000000001","status":"https://h.example.invalid/x","error_type":"ok_type"}"""),
        ).jsonObject
        assertEquals("s_1", scrubbed["message"]!!.jsonPrimitive.content)
        assertEquals("https://cdn.example.invalid/m/1", scrubbed["status"]!!.jsonPrimitive.content)
        assertEquals("ok_type", scrubbed["error_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aVisibleKeyHoldingAForbiddenWordIsScrubbedEvenInPlainText() {
        val scrubbed = Scrubber().scrub(
            parse("""{"message":"Your ${"session" + "id"} expired","feedback_title":"Bad ${"csrf" + "token"}","status":"fine"}"""),
        ).jsonObject
        assertEquals("s_1", scrubbed["message"]!!.jsonPrimitive.content)
        assertEquals("s_2", scrubbed["feedback_title"]!!.jsonPrimitive.content)
        assertEquals("fine", scrubbed["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun arraysAreScrubbedInFullNotJustTheFirstElement() {
        val scrubbed = Scrubber().scrub(parse("""{"names":["a1","b2","c3"],"nested":[[1,2],[3]]}""")).jsonObject
        assertEquals(listOf("s_1", "s_2", "s_3"), scrubbed["names"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(parse("""[[1,2],[3]]"""), scrubbed["nested"])
    }

    @Test
    fun aRootThatIsNotAnObjectIsScrubbedToo() {
        assertEquals(parse("""["s_1","s_2"]"""), Scrubber().scrub(parse("""["a1","b2"]""")))
        assertEquals(parse("null"), Scrubber().scrub(parse("null")))
        assertEquals(parse("true"), Scrubber().scrub(parse("true")))
    }

    private fun assertNoForbiddenWord(text: String, label: String) {
        for (word in forbidden) assertFalse(text.contains(word, ignoreCase = true), "$label holds '$word'")
    }
}
