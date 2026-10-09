package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.InstagramException.ChallengeRequired
import io.github.yuriimurha.reels.instagram.InstagramException.LoginRequired
import io.github.yuriimurha.reels.instagram.InstagramException.RateLimited
import io.github.yuriimurha.reels.instagram.InstagramException.ShapeChanged
import io.github.yuriimurha.reels.instagram.InstagramException.Transient
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebParsersTest {
    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/web/$name")!!.readText()).jsonObject

    /** [change] applied to item [index] (the `{"media": ...}` wrapper). */
    private fun JsonObject.withWrapper(index: Int, change: (JsonObject) -> JsonObject): JsonObject {
        val items = getValue("items").jsonArray.toMutableList()
        items[index] = change(items[index].jsonObject)
        return JsonObject(this + ("items" to JsonArray(items)))
    }

    /** [change] applied to the media object inside item [index]. */
    private fun JsonObject.withMedia(index: Int, change: (JsonObject) -> JsonObject): JsonObject =
        withWrapper(index) { it.with("media", change(it.getValue("media").jsonObject)) }

    private fun JsonObject.without(key: String) = JsonObject(filterKeys { it != key })

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun mediaOf(pageName: String, index: Int): RemoteMedia = WebParsers.savedPage(fixture(pageName)).items[index]

    private fun assertSavedShapeChange(path: String, json: JsonObject) =
        assertEquals(path, assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)

    /** The first media of `saved_page_more.json` (the reel) with [key] set to [value], parsed. */
    private fun reelWith(key: String, value: JsonElement): RemoteMedia =
        WebParsers.savedPage(fixture("saved_page_more.json").withMedia(0) { it.with(key, value) }).items[0]

    private fun num(literal: String): JsonElement = Json.parseToJsonElement(literal)

    @Test
    fun savedPageMapsEveryType() {
        val page = WebParsers.savedPage(fixture("saved_page_more.json"))
        assertEquals(listOf(MediaType.REEL, MediaType.IMAGE, MediaType.CAROUSEL), page.items.map { it.type })
        assertEquals("QVFE_cursor_2", page.nextCursor)

        val reel = page.items[0]
        assertEquals("3100000000000000001", reel.pk)
        assertEquals("Cx1aBcDeFg1", reel.code)
        assertEquals("user_1", reel.author)
        assertEquals("Synthetic caption one", reel.caption)
        assertEquals(Instant.ofEpochSecond(1700003000), reel.takenAt)
        assertEquals(1080, reel.width)
        assertEquals(1920, reel.height)
        assertNull(reel.carouselCount)
        assertEquals("https://cdn.example.invalid/v/t51/102.jpg?stp=x&oe=6720A3F0&_nc_ht=cdn", reel.thumbnailUrl)
        assertEquals("https://cdn.example.invalid/v/t50/104.mp4?stp=x&oe=6720A3F0&_nc_ht=cdn", reel.videoUrl)
        assertEquals(Instant.ofEpochSecond(0x6720A3F0), reel.videoUrlExpiresAt)
        assertEquals(listOf("17900000000000002"), reel.savedCollectionIds)

        val image = page.items[1]
        assertEquals("3100000000000000002", image.pk)
        assertNull(image.caption)
        assertNull(image.savedCollectionIds)
        assertNull(image.videoUrl)
        assertNull(image.videoUrlExpiresAt)
        assertEquals(Instant.ofEpochSecond(1700002000), image.takenAt)
        assertEquals("https://cdn.example.invalid/v/t51/202.jpg?stp=x&oe=6720A3F0&_nc_ht=cdn", image.thumbnailUrl)

        val carousel = page.items[2]
        assertEquals("3100000000000000003", carousel.pk)
        assertEquals(3, carousel.carouselCount)
        assertEquals("https://cdn.example.invalid/v/t51/302.jpg?stp=x&oe=6720A3F0&_nc_ht=cdn", carousel.thumbnailUrl)
        assertEquals(Instant.ofEpochSecond(1700001000), carousel.takenAt)
    }

    @Test
    fun lastPageHasNoCursor() {
        val page = WebParsers.savedPage(fixture("saved_page_last.json"))
        assertEquals(1, page.items.size)
        assertNull(page.nextCursor)
    }

    @Test
    fun collectionPageReadsLikeASavedPage() {
        val page = WebParsers.savedPage(fixture("collection_page.json"))
        assertEquals(listOf("3100000000000000005", "3100000000000000006"), page.items.map { it.pk })
        assertNull(page.nextCursor)
    }

    @Test
    fun unavailableItemsDoNotStopThePage() {
        val page = WebParsers.savedPage(fixture("saved_page_unavailable.json"))
        assertEquals(2, page.items.size)
        assertNull(page.nextCursor)
        assertEquals("3100000000000000007", page.items[0].pk)
        val bare = page.items[1]
        assertEquals("3100000000000000008", bare.pk)
        assertEquals("", bare.thumbnailUrl)
        assertEquals(0, bare.width)
        assertEquals(0, bare.height)
    }

    @Test
    fun missingMoreAvailableIsAShapeChange() {
        val json = fixture("saved_page_last.json").without("more_available")
        assertEquals("more_available", assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)
    }

    @Test
    fun moreAvailableWithoutCursorIsAShapeChange() {
        val json = fixture("saved_page_more.json").without("next_max_id")
        assertEquals("next_max_id", assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)
    }

    @Test
    fun missingRequiredFieldNamesItsPath() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("items[1].media.user", more.withMedia(1) { it.without("user") })
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.without("pk") })
        assertSavedShapeChange("items[0].media.code", more.withMedia(0) { it.without("code") })
        assertSavedShapeChange("items[0].media.taken_at", more.withMedia(0) { it.without("taken_at") })
        assertSavedShapeChange("items[0].media.user.username", more.withMedia(0) { it.with("user", JsonObject(emptyMap())) })
        assertSavedShapeChange("items[0]", more.with("items", JsonArray(listOf(JsonPrimitive("x")))))
    }

    @Test
    fun aMissingOrMistypedMediaIsAShapeChangeNotASkippedItem() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("items[1].media", more.withWrapper(1) { it.without("media") })
        assertSavedShapeChange("items[1].media", more.withWrapper(1) { JsonObject(mapOf("medium" to it.getValue("media"))) })
        assertSavedShapeChange("items[1].media", more.withWrapper(1) { it.with("media", JsonPrimitive("x")) })
        assertSavedShapeChange("items[1].media", more.withWrapper(1) { it.with("media", JsonArray(emptyList())) })
    }

    @Test
    fun anExplicitNullMediaIsSkipped() {
        val more = fixture("saved_page_more.json").withWrapper(0) { it.with("media", JsonNull) }
        assertEquals(listOf("3100000000000000002", "3100000000000000003"), WebParsers.savedPage(more).items.map { it.pk })
    }

    @Test
    fun moreAvailableThatIsNotABooleanIsAShapeChange() {
        val last = fixture("saved_page_last.json")
        assertSavedShapeChange("more_available", last.with("more_available", JsonPrimitive("false")))
        assertSavedShapeChange("more_available", last.with("more_available", JsonPrimitive("true")))
        assertSavedShapeChange("more_available", last.with("more_available", JsonNull))
        assertSavedShapeChange("more_available", last.with("more_available", JsonPrimitive(0)))
    }

    @Test
    fun anEmptyOrNullCursorIsAShapeChange() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("next_max_id", more.with("next_max_id", JsonPrimitive("")))
        assertSavedShapeChange("next_max_id", more.with("next_max_id", JsonNull))
    }

    @Test
    fun aPkThatIsNotDigitsIsAShapeChange() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.with("pk", JsonPrimitive("x")) })
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.with("pk", num("3.1E18")) })
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.with("pk", JsonPrimitive("1".repeat(31))) })
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.with("pk", JsonPrimitive("")) })
    }

    @Test
    fun aPkSentAsAStringOrNumberKeepsItsExactDigits() {
        assertEquals("3100000000000000001", reelWith("pk", JsonPrimitive("3100000000000000001")).pk)
        assertEquals("3100000000000000001", reelWith("pk", num("3100000000000000001")).pk)
    }

    @Test
    fun aTakenAtOutsideTheInstantRangeIsAShapeChange() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("items[0].media.taken_at", more.withMedia(0) { it.with("taken_at", JsonPrimitive(Long.MAX_VALUE)) })
    }

    @Test
    fun savedCollectionIdsThatAreNotAllStringsMeanTheResponseDoesNotSay() {
        val mixed = JsonArray(listOf(JsonPrimitive(1), JsonObject(mapOf("a" to JsonPrimitive(1)))))
        assertNull(reelWith("saved_collection_ids", mixed).savedCollectionIds)
        // One bad entry must not shorten the list: a shorter list would read as "not saved there" and delete memberships.
        val partlyBad = JsonArray(listOf(JsonPrimitive("17900000000000002"), JsonPrimitive(1)))
        assertNull(reelWith("saved_collection_ids", partlyBad).savedCollectionIds)
        assertNull(reelWith("saved_collection_ids", JsonArray(listOf(JsonPrimitive("17900000000000002"), JsonNull))).savedCollectionIds)
        assertEquals(emptyList(), reelWith("saved_collection_ids", JsonArray(emptyList())).savedCollectionIds)
    }

    @Test
    fun theCarouselCountComesFromTheFieldAndFallsBackToTheChildren() {
        val more = fixture("saved_page_more.json")
        fun carousel(count: JsonElement?): RemoteMedia = WebParsers.savedPage(
            more.withMedia(2) { media ->
                val twoChildren = JsonArray(media.getValue("carousel_media").jsonArray.take(2))
                val withoutCount = media.without("carousel_media_count").with("carousel_media", twoChildren)
                if (count == null) withoutCount else withoutCount.with("carousel_media_count", count)
            },
        ).items[2]
        assertEquals(2, carousel(null).carouselCount)
        assertEquals(5, carousel(JsonPrimitive(5)).carouselCount)
    }

    @Test
    fun aCarouselWithNeitherCountNorChildrenHasNoCount() {
        val more = fixture("saved_page_more.json").withMedia(2) { it.without("carousel_media_count").without("carousel_media") }
        val carousel = WebParsers.savedPage(more).items[2]
        assertNull(carousel.carouselCount)
        assertEquals("", carousel.thumbnailUrl)
    }

    @Test
    fun unknownMediaTypeIsAShapeChange() {
        val json = fixture("saved_page_more.json").withMedia(0) { it.with("media_type", JsonPrimitive(5)) }
        assertEquals("items[0].media.media_type", assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)
    }

    @Test
    fun missingItemsIsAShapeChange() {
        assertEquals("items", assertFailsWith<ShapeChanged> { WebParsers.savedPage(JsonObject(emptyMap())) }.fieldPath)
    }

    // Replies to the website's Saved-tab GraphQL query (spec 2026-10-09 §3.1), scrubbed: names Alpha and Beta, fixture ids.
    private fun node(id: String, name: String, cover: String? = null) =
        """{"node":{"__typename":"XDTSavedCollection","collection_id":"$id","collection_name":${JsonPrimitive(name)},""" +
            """"collection_media_count":1,"cover_media":${cover?.let { "{\"pk\":\"$it\"}" } ?: "null"}},"cursor":"x"}"""

    private fun reply(vararg edges: String, next: String? = null) =
        """{"data":{"viewer":{"collections_unified_with_auto_collections":{"edges":[${edges.joinToString(",")}],""" +
            """"page_info":{"has_next_page":${next != null},"end_cursor":${next?.let { "\"$it\"" } ?: "null"}}}}}}"""

    private fun collections(body: String) = WebParsers.collectionsGraphQl(Json.parseToJsonElement(body).jsonObject)

    private fun assertCollectionsShapeChange(path: String, body: String) =
        assertEquals(path, assertFailsWith<ShapeChanged> { collections(body) }.fieldPath)

    private fun graphQl(body: String?, code: Int = 200, contentType: String? = "application/json") = RawReply(code, contentType, body)

    private val alpha = node("17900000000000001", "Alpha", "3100000000000000001")

    /** The collections root with [rest] after its `{`: the caller closes the root and the three objects around it. */
    private fun root(rest: String) = """{"data":{"viewer":{"collections_unified_with_auto_collections":{$rest}}}}"""

    @Test
    fun collectionsGraphQlKeepsUserCollectionsAndSkipsAutomaticOnes() {
        val page = collections(
            reply(
                node("ALL_MEDIA_AUTO_COLLECTION", "All posts", "3100000000000000009"),
                alpha,
                node("17900000000000002", "Beta"),
                node("AUDIO_AUTO_COLLECTION", "Audio"),
            ),
        )
        assertEquals(
            listOf(
                RemoteCollection("17900000000000001", "Alpha", "3100000000000000001"),
                RemoteCollection("17900000000000002", "Beta", null),
            ),
            page.items,
        )
        assertNull(page.nextCursor)
    }

    @Test
    fun aLeadingForLoopGuardIsStripped() {
        val guarded = graphQl("for (;;);" + reply(alpha))
        assertNull(WebParsers.classifySavedCollections(guarded))
        assertEquals(listOf("Alpha"), WebParsers.collectionsGraphQl(WebParsers.savedCollectionsJsonOrThrow(guarded)).items.map { it.name })
        // The guard hides nothing the reply says.
        assertIs<LoginRequired>(WebParsers.classifySavedCollections(graphQl("for (;;);" + """{"require_login":true}""")))
        assertIs<LoginRequired>(assertFailsWith<InstagramException> { WebParsers.savedCollectionsJsonOrThrow(graphQl("""{"require_login":true}""")) })
    }

    @Test
    fun page2CursorComesFromPageInfo() {
        assertEquals("c1", collections(reply(alpha, next = "c1")).nextCursor)
        assertNull(collections(reply(alpha)).nextCursor)
        assertCollectionsShapeChange("page_info", root(""""edges":[]"""))
    }

    @Test
    fun aPageThatSaysMoreWithoutACursorOrNotAsABooleanIsAShapeChange() {
        assertCollectionsShapeChange("page_info.end_cursor", root(""""edges":[],"page_info":{"has_next_page":true,"end_cursor":null}"""))
        assertCollectionsShapeChange("page_info.end_cursor", root(""""edges":[],"page_info":{"has_next_page":true,"end_cursor":""}"""))
        // A real JSON boolean only: the string "false" must not read as "last page" (P5).
        assertCollectionsShapeChange("page_info.has_next_page", root(""""edges":[],"page_info":{"has_next_page":"false"}"""))
        assertCollectionsShapeChange("page_info.has_next_page", root(""""edges":[],"page_info":{"end_cursor":null}"""))
        assertCollectionsShapeChange("page_info", root(""""edges":[],"page_info":[]"""))
    }

    @Test
    fun namesAreKeptAsIs() {
        val odd = "Q\"uote 😀 שלום"
        val page = collections(reply(node("17900000000000001", odd), node("17900000000000002", "")))
        // An empty name stays empty here; sync gives it a placeholder (Task 5).
        assertEquals(listOf(odd, ""), page.items.map { it.name })
    }

    @Test
    fun aNodeWithoutItsIdOrNameIsAShapeChange() {
        assertCollectionsShapeChange("edges[1].node.collection_id", reply(alpha, """{"node":{"collection_name":"Beta"}}"""))
        assertCollectionsShapeChange("edges[1].node.collection_id", reply(alpha, """{"node":{"collection_id":null,"collection_name":"Beta"}}"""))
        assertCollectionsShapeChange("edges[1].node.collection_name", reply(alpha, """{"node":{"collection_id":"17900000000000002"}}"""))
        assertCollectionsShapeChange(
            "edges[1].node.collection_name",
            reply(alpha, """{"node":{"collection_id":"17900000000000002","collection_name":null}}"""),
        )
        assertCollectionsShapeChange("edges[1].node", reply(alpha, """{"cursor":"x"}"""))
        assertCollectionsShapeChange("edges[1].node", reply(alpha, """{"node":null,"cursor":"x"}"""))
        assertCollectionsShapeChange("edges[1]", reply(alpha, "7"))
        assertCollectionsShapeChange("edges", root(""""page_info":{"has_next_page":false}"""))
        assertCollectionsShapeChange("data.viewer.collections_unified_with_auto_collections", """{"data":{"viewer":{}}}""")
    }

    @Test
    fun anIdOfDigitsIsAUserCollectionAndAnythingElseIsAutomatic() {
        assertTrue(WebParsers.isUserCollectionId("17900000000000001"))
        for (auto in listOf("ALL_MEDIA_AUTO_COLLECTION", "AUDIO_AUTO_COLLECTION", "", "1790000000000000a", "-1", "1".repeat(31))) {
            assertFalse(WebParsers.isUserCollectionId(auto), auto)
        }
    }

    @Test
    fun aCoverPkThatIsNotDigitsIsJustNoCover() {
        fun alphaWithCover(pk: String) =
            collections(reply("""{"node":{"collection_id":"17900000000000001","collection_name":"Alpha","cover_media":{"pk":$pk}}}"""))
                .items.single().coverMediaPk
        assertNull(alphaWithCover("\"x\""))
        assertNull(alphaWithCover("3.1E18"))
        assertEquals("3100000000000000009", alphaWithCover("\"3100000000000000009\""))
        assertEquals("3100000000000000009", alphaWithCover("3100000000000000009"))
    }

    /** Fact STALE as R12 bounds it: the query did not run (no `data.viewer`), or the site answered a non-JSON 400/404. */
    @Test
    fun classifySavedCollectionsCallsFactStalesReplyAStaleQuery() {
        val stale = listOf(
            graphQl("""{"errors":[{"message":"x","severity":"CRITICAL"}],"data":null}"""),
            graphQl("""{"errors":[{"message":"x"}]}"""),
            graphQl("""{"errors":[{"message":"x"}],"data":{}}"""),
            graphQl("""{"errors":[{"message":"x"}],"data":null,"status":"fail"}"""),
            graphQl("for (;;);" + """{"errors":[{"message":"x","summary":"y","description":"z"}],"data":null}"""),
            graphQl("<html><body>Sorry, this page isn't available.</body></html>", code = 404, contentType = "text/html"),
            graphQl("Bad request", code = 400, contentType = "text/plain"),
        )
        for (reply in stale) {
            val error = assertIs<InstagramException.StaleQuery>(WebParsers.classifySavedCollections(reply), reply.body)
            assertEquals(WebGraphQl.SAVED_COLLECTIONS.friendlyName, error.query)
            assertFalse(WebGraphQl.SAVED_COLLECTIONS.builtInDocId in error.message.orEmpty(), "a StaleQuery never carries a doc id")
            assertIs<InstagramException.StaleQuery>(assertFailsWith<InstagramException> { WebParsers.savedCollectionsJsonOrThrow(reply) })
        }
    }

    /** R12 (a): an `errors` entry that names a rate limit, a logout or a challenge is that, never a stale query. */
    @Test
    fun anInBandRateLimitLoginOrChallengeKeepsItsMeaning() {
        val rootNull = """"data":{"viewer":{"collections_unified_with_auto_collections":null}}"""
        val wait = """{"message":"Please wait a few minutes before you try again."}"""
        // The throttled account of the review: before R12 this was a stale query, and would have started a repair.
        assertIs<RateLimited>(WebParsers.classifySavedCollections(graphQl("""{$rootNull,"errors":[$wait]}""")))
        assertIs<RateLimited>(WebParsers.classifySavedCollections(graphQl("""{"data":null,"errors":[$wait]}""")))
        assertIs<RateLimited>(WebParsers.classifySavedCollections(graphQl("""{"errors":[{"summary":"feedback_required"}]}""")))
        assertIs<RateLimited>(WebParsers.classifySavedCollections(graphQl("for (;;);" + """{"errors":[$wait],""" + reply(alpha).removePrefix("{"))))
        assertIs<RateLimited>(WebParsers.classifySavedCollections(graphQl("""{"errors":[$wait]}""", code = 400)))

        assertIs<LoginRequired>(WebParsers.classifySavedCollections(graphQl("""{"data":null,"errors":[{"message":"x","summary":"login_required"}]}""")))
        assertIs<LoginRequired>(WebParsers.classifySavedCollections(graphQl("""{$rootNull,"errors":[{"description":"Login_Required"}]}""")))

        val challenge = assertIs<ChallengeRequired>(
            WebParsers.classifySavedCollections(graphQl("""{"data":null,"errors":[{"description":"challenge_required"}]}""")),
        )
        assertNull(challenge.challengeUrl, "an in-band challenge has no URL")
        assertIs<ChallengeRequired>(WebParsers.classifySavedCollections(graphQl("""{"errors":[{"message":"checkpoint_required"}]}""")))

        // Across entries, ErrorClassifier's precedence: a challenge, then a rate limit, then a logout.
        val login = """{"message":"login_required"}"""
        assertIs<RateLimited>(WebParsers.classifySavedCollections(graphQl("""{"data":null,"errors":[$login,$wait]}""")))
        assertIs<ChallengeRequired>(
            WebParsers.classifySavedCollections(graphQl("""{"data":null,"errors":[$wait,{"summary":"challenge_required"}]}""")),
        )
    }

    /** R12 (b): with `data.viewer` the query ran, so its doc id is current: an error there is a passing failure, never stale. */
    @Test
    fun aReplyWhoseQueryRanIsNeverStale() {
        val execution = """"errors":[{"message":"An unknown error occurred.","severity":"ERROR"}]"""
        for (data in listOf(
            """{"viewer":{"collections_unified_with_auto_collections":null}}""",
            """{"viewer":{}}""",
            """{"viewer":null}""",
        )) {
            assertIs<Transient>(WebParsers.classifySavedCollections(graphQl("""{"data":$data,$execution}""")), data)
            assertIs<Transient>(WebParsers.classifySavedCollections(graphQl("for (;;);" + """{"data":$data,$execution}""")), data)
        }
    }

    @Test
    fun classifySavedCollectionsKeepsEveryOtherFailure() {
        assertIs<RateLimited>(WebParsers.classifySavedCollections(graphQl("""{"status":"fail"}""", code = 429)))
        assertIs<LoginRequired>(WebParsers.classifySavedCollections(graphQl("""{"require_login":true}""")))
        assertNull(assertIs<ChallengeRequired>(WebParsers.classifySavedCollections(RawReply(302, null, null, redirected = true))).challengeUrl)
        assertIs<Transient>(WebParsers.classifySavedCollections(graphQl("<html>down</html>", code = 500, contentType = "text/html")))
        assertIs<Transient>(WebParsers.classifySavedCollections(graphQl(null)))
        // A challenge or a rate limit wins over the errors of a stale reply.
        assertIs<ChallengeRequired>(WebParsers.classifySavedCollections(graphQl("""{"message":"challenge_required","errors":[{"message":"x"}]}""")))
        assertIs<RateLimited>(WebParsers.classifySavedCollections(graphQl("""{"errors":[{"message":"x"}]}""", code = 429)))
        // A cut 400 may have hidden a challenge, and a JSON 400 or a 410 is not fact STALE's reply: none is a stale query.
        assertEquals("http.400.unreadable", assertIs<ShapeChanged>(WebParsers.classifySavedCollections(graphQl(null, code = 400))).fieldPath)
        assertEquals("http.400", assertIs<ShapeChanged>(WebParsers.classifySavedCollections(graphQl("""{"status":"fail"}""", code = 400))).fieldPath)
        assertEquals("http.410", assertIs<ShapeChanged>(WebParsers.classifySavedCollections(graphQl("gone", code = 410))).fieldPath)
        // A 200 that is not JSON keeps the rule of every other reply.
        assertIs<LoginRequired>(WebParsers.classifySavedCollections(graphQl("<html>Log in</html>", contentType = "text/html")))
        assertEquals("$", assertIs<ShapeChanged>(WebParsers.classifySavedCollections(graphQl("oops", contentType = "text/plain"))).fieldPath)
    }

    @Test
    fun classifySavedCollectionsLetsAReplyWithTheRootBeParsed() {
        assertNull(WebParsers.classifySavedCollections(graphQl(reply(alpha))))
        // Errors beside the root (a partial answer) still carry the collections.
        assertNull(WebParsers.classifySavedCollections(graphQl("""{"errors":[{"message":"x"}],""" + reply(alpha).removePrefix("{"))))
    }

    @Test
    fun classifySavedCollectionsCallsDataWithoutTheRootOrErrorsAShapeChange() {
        for (body in listOf("""{"data":{"viewer":{}}}""", """{"data":{"viewer":null}}""", """{"data":{}}""", "{}", """{"errors":[]}""")) {
            assertEquals(
                "data.viewer.collections_unified_with_auto_collections",
                assertIs<ShapeChanged>(WebParsers.classifySavedCollections(graphQl(body)), body).fieldPath,
            )
        }
    }

    @Test
    fun mediaInfoReadsTheFirstItem() {
        val info = fixture("media_info.json")
        val reel = mediaOf("saved_page_more.json", 0)
        assertEquals(reel, assertNotNull(WebParsers.mediaInfo(info)))
        // With a second item present, the first still wins.
        val image = fixture("saved_page_more.json").getValue("items").jsonArray[1].jsonObject.getValue("media")
        val two = info.with("items", JsonArray(listOf(info.getValue("items").jsonArray[0], image)))
        assertEquals(reel, WebParsers.mediaInfo(two))
        val empty = JsonObject(mapOf("items" to JsonArray(emptyList()), "status" to JsonPrimitive("ok")))
        assertNull(WebParsers.mediaInfo(empty))
    }

    @Test
    fun mediaInfoNamesThePathOfAMissingField() {
        val info = fixture("media_info.json")
        val first = info.getValue("items").jsonArray[0].jsonObject
        val noCode = info.with("items", JsonArray(listOf(first.without("code"))))
        assertEquals("items[0].code", assertFailsWith<ShapeChanged> { WebParsers.mediaInfo(noCode) }.fieldPath)
        val notAnObject = info.with("items", JsonArray(listOf(JsonPrimitive("x"))))
        assertEquals("items[0]", assertFailsWith<ShapeChanged> { WebParsers.mediaInfo(notAnObject) }.fieldPath)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val extra = JsonObject(mapOf("x" to JsonPrimitive(1)))
        val base = fixture("saved_page_more.json")
        // The new key sits on the page, on every item wrapper and on every media object.
        val items = base.getValue("items").jsonArray.map { wrapper ->
            val media = wrapper.jsonObject.getValue("media").jsonObject.with("brand_new_key", extra)
            wrapper.jsonObject.with("brand_new_key", extra).with("media", media)
        }
        val json = base.with("brand_new_key", extra).with("items", JsonArray(items))
        assertEquals(WebParsers.savedPage(base), WebParsers.savedPage(json))
    }
}
