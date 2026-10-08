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
    fun aFreshScrubberStartsItsCountersOverWhileOneScrubberKeepsCounting() {
        fun cursor(scrubbed: JsonElement) = scrubbed.jsonObject["next_max_id"]!!.jsonPrimitive.content

        val shared = Scrubber()
        shared.scrub(fixture("collections_list.json")) // four collection names: s_1 to s_4
        val afterOther = shared.scrub(fixture("saved_page_more.json"))
        val fresh = Scrubber().scrub(fixture("saved_page_more.json"))

        assertEquals("s_1", cursor(fresh))
        assertEquals("s_5", cursor(afterOther))
        assertNotEquals(render(afterOther), render(fresh))
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
        assertEquals("https://cdn.example.invalid/m/1?oe=x&k=x&k=x&p_3=x", scrubbed["a"]!!.jsonPrimitive.content)
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
        assertEquals("text 1", scrubbed["message"]!!.jsonPrimitive.content)
        assertEquals("text 2", scrubbed["status"]!!.jsonPrimitive.content)
        assertEquals("ok_type", scrubbed["error_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun aVisibleKeyHoldingAForbiddenWordIsScrubbedEvenInPlainText() {
        val scrubbed = Scrubber().scrub(
            parse("""{"message":"Your ${"session" + "id"} expired","feedback_title":"Bad ${"csrf" + "token"}","status":"fine"}"""),
        ).jsonObject
        assertEquals("text 1", scrubbed["message"]!!.jsonPrimitive.content)
        assertEquals("text 2", scrubbed["feedback_title"]!!.jsonPrimitive.content)
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

    // ---- visible-value keys: the Scrubber output may be committed to a public repo, so it is stricter than the screen ----

    private fun scrubVisible(key: String, value: String): String =
        Scrubber().scrub(JsonObject(mapOf(key to JsonPrimitive(value)))).jsonObject[key]!!.jsonPrimitive.content

    private fun assertRedactedInTheScrubber(key: String, value: String) =
        assertEquals("text 1", scrubVisible(key, value), "'$value' under $key must not survive")

    @Test
    fun aHandleUnderMessageIsRedactedInTheScrubber() = assertRedactedInTheScrubber("message", "john.doe")

    @Test
    fun aHandleInASentenceIsRedactedInTheScrubber() = assertRedactedInTheScrubber("message", "Sorry, john.doe is private")

    @Test
    fun aFullNameUnderFeedbackTitleIsRedactedInTheScrubber() = assertRedactedInTheScrubber("feedback_title", "John Doe")

    @Test
    fun aCommaGroupedIdIsRedactedInTheScrubber() = assertRedactedInTheScrubber("message", "Item 3,100,000,000,000,000,001 gone")

    @Test
    fun aHyphenGroupedIdIsRedactedInTheScrubber() = assertRedactedInTheScrubber("message", "pk 3100-0000-0000-0000-001")

    @Test
    fun aSpaceGroupedIdIsRedactedInTheScrubber() = assertRedactedInTheScrubber("message", "user 3100 0000 0000 0000 001")

    @Test
    fun anIdSplitByColonsOrParenthesesIsRedactedInTheScrubber() {
        assertRedactedInTheScrubber("message", "id 31:00:00:00:00")
        assertRedactedInTheScrubber("message", "(31)(00)(00)")
        assertRedactedInTheScrubber("message", "31:00:00:00")
    }

    @Test
    fun aSentenceWithSmallNumbersSurvivesInTheScrubber() {
        assertEquals("Wait 2 minutes, 30 seconds", scrubVisible("message", "Wait 2 minutes, 30 seconds"))
    }

    @Test
    fun aThreeDigitRunIsRedactedInTheScrubber() {
        assertRedactedInTheScrubber("message", "Code 1234")
        assertEquals("text 1", scrubVisible("message", "Item 1 2 3"))
    }

    @Test
    fun anEmailishAHostWithAnUncommonTldAndAnUppercaseHostAreRedactedInTheScrubber() {
        assertRedactedInTheScrubber("message", "john.doe at gmail dot com")
        assertRedactedInTheScrubber("message", "see evil.example.xyz now")
        assertRedactedInTheScrubber("message", "see WWW.EXAMPLE.ORG now")
    }

    @Test
    fun nonAsciiAndOverlongVisibleStringsAreRedactedInTheScrubber() {
        assertRedactedInTheScrubber("message", "Жанна")
        // A sentence is at most 200 characters.
        assertRedactedInTheScrubber("message", "a".repeat(201))
        assertEquals("a".repeat(200), scrubVisible("message", "a".repeat(200)))
    }

    @Test
    fun nestedArraysUnderAVisibleKeyAreRedactedInTheScrubber() {
        val scrubbed = Scrubber().scrub(parse("""{"message":[["john.doe","Please wait a few minutes before you try again."]]}"""))
        assertEquals(parse("""{"message":[["text 1","Please wait a few minutes before you try again."]]}"""), scrubbed)
    }

    @Test
    fun aHandleUnderStatusOrErrorTypeIsAcceptedAsEnumLikeAndSurvives() {
        // Documented limit: one word of letters or underscores under an enum-like key cannot be told from an enum value.
        val scrubbed = Scrubber().scrub(parse("""{"status":"johndoe","error_type":"jane_doe"}"""))
        assertEquals(parse("""{"status":"johndoe","error_type":"jane_doe"}"""), scrubbed)
    }

    @Test
    fun enumValuesAndPlainSentencesStillSurvive() {
        assertEquals("ALL_MEDIA_AUTO_COLLECTION", scrubVisible("collection_type", "ALL_MEDIA_AUTO_COLLECTION"))
        assertEquals("challenge_required", scrubVisible("message", "challenge_required"))
        assertEquals("fail", scrubVisible("status", "fail"))
        val sentence = "Please wait a few minutes before you try again."
        assertEquals(sentence, scrubVisible("message", sentence))
        assertEquals("Try again later", scrubVisible("feedback_title", "Try again later"))
        assertEquals("Wait 2 minutes, 30 seconds", scrubVisible("message", "Wait 2 minutes, 30 seconds"))
        // Capitalised words in a row are a name until proven otherwise.
        assertEquals("text 1", scrubVisible("feedback_title", "Try Again Later"))
    }

    @Test
    fun aVisibleNumberSurvivesOnlyWithSixDigitsOrFewer() {
        val scrubbed = Scrubber().scrub(parse("""{"width":123456,"height":1234567,"status":25025320,"original_width":250253201,"num_results":3}""")).jsonObject
        assertEquals("123456", scrubbed["width"]!!.jsonPrimitive.content)
        assertEquals("1", scrubbed["height"]!!.jsonPrimitive.content)
        assertEquals("2", scrubbed["status"]!!.jsonPrimitive.content)
        assertEquals("3", scrubbed["original_width"]!!.jsonPrimitive.content)
        assertEquals("3", scrubbed["num_results"]!!.jsonPrimitive.content)
    }

    // ---- names ----

    @Test
    fun aHashLikeAMixedCaseAndADigitHeavyKeyAreRedacted() {
        val scrubbed = Scrubber().scrub(
            parse("""{"ab12cd34ef56ab78cd90ab12":1,"AbCdEfGhIjKlMnOpQrStUv":2,"u3100_0000_0000_0000_001":3,"Media":4,"abcdefghijklmno1":5}"""),
        ).jsonObject
        assertEquals(listOf("key_1", "key_2", "key_3", "key_4", "key_5"), scrubbed.keys.toList())
    }

    @Test
    fun aKeyOfSixteenLettersWithoutADigitAndAShortOneWithADigitAreKept() {
        val scrubbed = Scrubber().scrub(parse("""{"abcdefghijklmnop":1,"image_versions2":2,"a1":3,"is_dash_eligible":4}""")).jsonObject
        assertEquals(listOf("abcdefghijklmnop", "image_versions2", "a1", "is_dash_eligible"), scrubbed.keys.toList())
    }

    @Test
    fun aBareLowercaseKeyIsKeptBecauseItCannotBeToldFromASchemaKey() {
        // Documented limit (ARCHITECTURE.md): read a scrubbed file before committing it.
        val scrubbed = Scrubber().scrub(parse("""{"johndoe":{"a":1},"jane_doe":2}"""))
        assertEquals(parse("""{"johndoe":{"a":1},"jane_doe":2}"""), scrubbed)
    }

    @Test
    fun unicodeKeysAreRedactedAndUnicodeValuesAreScrubbed() {
        val scrubbed = Scrubber().scrub(parse("""{"jöhn":1,"text":"Ｊｏｈｎ","note":"Жанна"}""")).jsonObject
        assertEquals(listOf("key_1", "text", "note"), scrubbed.keys.toList())
        assertEquals("caption 1", scrubbed["text"]!!.jsonPrimitive.content)
        assertEquals("s_1", scrubbed["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun aHandleAnIdAndALinkInsideAnOrdinaryStringAreScrubbedWhole() {
        val scrubbed = Scrubber().scrub(
            parse("""{"accessibility_caption":"Photo by john.doe id 3100000000000000001 https://x.example.invalid/p"}"""),
        )
        assertEquals(parse("""{"accessibility_caption":"s_1"}"""), scrubbed)
    }

    @Test
    fun overlongNonVisibleStringsAreScrubbed() {
        val scrubbed = Scrubber().scrub(JsonObject(mapOf("note" to JsonPrimitive("b".repeat(5000))))).jsonObject
        assertEquals("s_1", scrubbed["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun urlParameterNamesThatAreDataAreRedactedAndTheRestAreKept() {
        val scrubbed = Scrubber().scrub(
            parse("""{"u":"https://h.example.invalid/a?AbCd=1&oe=deadbeefcafebabe&ab12cd34ef56ab78cd90ab12=2&johndoe=3"}"""),
        ).jsonObject
        // johndoe is a bare lowercase name: kept, like a lowercase key (documented limit). Its value is x.
        assertEquals(
            "https://cdn.example.invalid/m/1?p_0=x&oe=deadbeefcafebabe&p_2=x&johndoe=x",
            scrubbed["u"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun aSyntheticKeyNeverOverwritesARealOne() {
        val scrubbed = Scrubber().scrub(parse("""{"key_1":1,"john.doe":2}""")).jsonObject
        assertEquals(2, scrubbed.size, scrubbed.toString())
        assertEquals("1", scrubbed["key_1"]!!.jsonPrimitive.content)
        val other = scrubbed.keys.single { it != "key_1" }
        assertEquals("2", scrubbed[other]!!.jsonPrimitive.content)
    }

    @Test
    fun severalSyntheticKeysAvoidEveryRealKeyAndEachOther() {
        val scrubbed = Scrubber().scrub(parse("""{"key_1":1,"a b":2,"c d":3,"key_2":4,"e f":5}""")).jsonObject
        assertEquals(5, scrubbed.size, scrubbed.toString())
        assertEquals("1", scrubbed["key_1"]!!.jsonPrimitive.content)
        assertEquals("4", scrubbed["key_2"]!!.jsonPrimitive.content)
        // The same real key maps to the same synthetic name in another object of the same document.
        val twice = Scrubber().scrub(parse("""{"x":{"a b":1},"y":{"a b":2}}""")).jsonObject
        assertEquals(twice["x"]!!.jsonObject.keys, twice["y"]!!.jsonObject.keys)
    }

    // ---- ids ----

    @Test
    fun twelveDistinctTwoDigitIdsGetTwelveDistinctStandIns() {
        val pks = (10..21).joinToString(",") { "{\"pk\":$it}" }
        val scrubbed = Scrubber().scrub(parse("""{"items":[$pks]}"""))
        val out = valuesOf(scrubbed, "pk")
        assertEquals(12, out.distinct().size, out.toString())
        out.zip(10..21).forEach { (synthetic, real) ->
            assertEquals(2, synthetic.length)
            assertNotEquals(real.toString(), synthetic)
        }
    }

    @Test
    fun theSpaceOfAGivenLengthRunsOutIntoALongerStandInInsteadOfRepeating() {
        val twoDigits = (10..99).joinToString(",") { "{\"pk\":$it}" }
        val out2 = valuesOf(Scrubber().scrub(parse("""{"items":[$twoDigits]}""")), "pk")
        assertEquals(90, out2.distinct().size)
        assertTrue(out2.first().length == 2 && out2.all { it.all { c -> c in '0'..'9' } })
        val oneDigit = (0..9).joinToString(",") { "{\"pk\":$it}" }
        val out1 = valuesOf(Scrubber().scrub(parse("""{"items":[$oneDigit]}""")), "pk")
        assertEquals(10, out1.distinct().size, out1.toString())
        out1.forEachIndexed { i, synthetic -> assertNotEquals(i.toString(), synthetic) }
    }

    // ---- memory ----

    /** Every String reachable from [root] through maps, collections, arrays and this package's own objects. */
    private fun heldStrings(root: Any): List<String> {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val out = mutableListOf<String>()
        fun walk(o: Any?) {
            if (o == null || seen.put(o, true) != null) return
            when (o) {
                is CharSequence -> out += o.toString()
                is Map<*, *> -> o.forEach { (k, v) -> walk(k); walk(v) }
                is Iterable<*> -> o.forEach { walk(it) }
                is Array<*> -> o.forEach { walk(it) }
                else -> if (o.javaClass.name.startsWith("io.github.yuriimurha.reels.instagram.lab")) {
                    var type: Class<*>? = o.javaClass
                    while (type != null && type != Any::class.java) {
                        type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.forEach { field ->
                            field.isAccessible = true
                            walk(field.get(o))
                        }
                        type = type.superclass
                    }
                }
            }
        }
        walk(root)
        return out
    }

    @Test
    fun theScrubberKeepsNoRawValueInMemory() {
        val scrubber = Scrubber()
        val raws = listOf(
            "3100000000000000001", "7700000001", "alice_secret", "Alice Secretson", "my secret caption", "Cx1aBcDeFg1",
            "https://h.example.invalid/very/secret/path?x=1", "john.doe", "Some Odd Visible Text", "plain string value", "987654321",
        )
        scrubber.scrub(
            parse(
                """{"pk":${raws[0]},"user":{"pk":${raws[1]},"username":"${raws[2]}","full_name":"${raws[3]}"},
                "caption":{"text":"${raws[4]}"},"code":"${raws[5]}","url":"${raws[6]}","${raws[7]}":1,
                "message":"${raws[8]}","note":"${raws[9]}","count":${raws[10]},"taken_at":1700003000,"strong_id":"${raws[0]}_42"}""",
            ),
        )
        val held = heldStrings(scrubber)
        assertTrue(held.isNotEmpty(), "the walker must see the mappings")
        for (raw in raws + "1700003000") assertTrue(held.none { raw in it }, "the Scrubber still holds '$raw'")
        // The mappings still work: the same scrubber maps the same id the same way.
        val again = scrubber.scrub(parse("""{"pk":${raws[0]}}""")).jsonObject["pk"]!!.jsonPrimitive.content
        assertEquals(19, again.length)
    }

    private fun assertNoForbiddenWord(text: String, label: String) {
        for (word in forbidden) assertFalse(text.contains(word, ignoreCase = true), "$label holds '$word'")
    }
}
