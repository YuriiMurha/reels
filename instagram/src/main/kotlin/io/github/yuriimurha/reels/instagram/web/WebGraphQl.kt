package io.github.yuriimurha.reels.instagram.web

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One GraphQL query the app may send: the website's own, by its friendly name. [builtInDocId] is today's id. */
class GraphQlQuery internal constructor(val friendlyName: String, val builtInDocId: String) {
    override fun toString() = "GraphQlQuery($friendlyName)"
}

/**
 * The website's GraphQL queries the app uses (spec 2026-10-09 §3.1), and how the website sends one. Nothing outside
 * `:instagram` spells one out; the hidden page's script repeats the form and header names, pinned against [FORM_FIELDS] and
 * [HEADERS] by its tests.
 */
object WebGraphQl {
    const val PATH = "api/graphql"

    /** The desktop website's Saved tab: the account's collections with their names. */
    val SAVED_COLLECTIONS = GraphQlQuery("PolarisProfileSavedTabContentQuery", "27584326974521636")

    /** Every query any page script may send; the scripts pin their own list against this. */
    val ALL: List<GraphQlQuery> = listOf(SAVED_COLLECTIONS)

    /**
     * The longest doc id sent. The allow-list names a query, but the server runs whatever persisted query the doc id names, so
     * only an id of the website's shape is ever sent: ASCII digits (today's has 17), at most this many.
     */
    const val DOC_ID_MAX_DIGITS = 30

    /** Whether [docId] has the shape of the website's doc ids ([DOC_ID_MAX_DIGITS]); a transport sends no other. */
    fun isDocId(docId: String): Boolean = docId.length in 1..DOC_ID_MAX_DIGITS && docId.all { it in '0'..'9' }

    /** The names of the website's form fields for a query. [DTSG] and [LSD] carry the page's own tokens. */
    object Field {
        const val DTSG = "fb_dtsg"
        const val LSD = "lsd"
        const val CALLER_CLASS = "fb_api_caller_class"
        const val FRIENDLY_NAME = "fb_api_req_friendly_name"
        const val VARIABLES = "variables"
        const val SERVER_TIMESTAMPS = "server_timestamps"
        const val DOC_ID = "doc_id"
    }

    /** Every form field the website posts for a query, in its order. */
    val FORM_FIELDS: List<String> = listOf(
        Field.DTSG,
        Field.LSD,
        Field.CALLER_CLASS,
        Field.FRIENDLY_NAME,
        Field.VARIABLES,
        Field.SERVER_TIMESTAMPS,
        Field.DOC_ID,
    )

    /** The website's [Field.CALLER_CLASS]. */
    const val CALLER_CLASS = "RelayModern"

    /** The names of the headers the website sends with a query (beside the browser's own). [LSD] carries the page's `lsd`. */
    object Header {
        const val CONTENT_TYPE = "content-type"
        const val FRIENDLY_NAME = "x-fb-friendly-name"
        const val LSD = "x-fb-lsd"
        const val APP_ID = "x-ig-app-id"
        const val ASBD_ID = "x-asbd-id"
        const val CSRF_TOKEN = "x-csrftoken"
    }

    /** Every header the website sends with a query, in its order. */
    val HEADERS: List<String> = listOf(
        Header.CONTENT_TYPE,
        Header.FRIENDLY_NAME,
        Header.LSD,
        Header.APP_ID,
        Header.ASBD_ID,
        Header.CSRF_TOKEN,
    )

    /** As the website sends them; [cursor] (page 2 on) goes in as `after` (Task 1 fact CURSOR). */
    fun savedCollectionsVariables(cursor: String?): String = buildJsonObject {
        put(
            "collection_types",
            buildJsonArray {
                listOf("ALL_MEDIA_AUTO_COLLECTION", "MEDIA", "AUDIO_AUTO_COLLECTION").forEach { add(JsonPrimitive(it)) }
            },
        )
        put("first", 12)
        if (cursor != null) put("after", cursor)
    }.toString()
}
