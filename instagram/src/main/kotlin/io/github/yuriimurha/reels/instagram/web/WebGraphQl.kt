package io.github.yuriimurha.reels.instagram.web

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One GraphQL query the app may send: the website's own, by its friendly name. [builtInDocId] is today's id. */
class GraphQlQuery internal constructor(val friendlyName: String, val builtInDocId: String) {
    override fun toString() = "GraphQlQuery($friendlyName)"
}

/** The website's GraphQL queries the app uses (spec 2026-10-09 §3.1). Nothing outside `:instagram` spells one out. */
object WebGraphQl {
    const val PATH = "api/graphql"

    /** The desktop website's Saved tab: the account's collections with their names. */
    val SAVED_COLLECTIONS = GraphQlQuery("PolarisProfileSavedTabContentQuery", "27584326974521636")

    /** Every query any page script may send; the scripts pin their own list against this. */
    val ALL: List<GraphQlQuery> = listOf(SAVED_COLLECTIONS)

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
