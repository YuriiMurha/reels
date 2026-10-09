package io.github.yuriimurha.reels.instagram.web

/**
 * The doc id the app sends for each of the website's GraphQL queries (spec 2026-10-09 §3.3): the one learned from the site
 * last, or the query's [GraphQlQuery.builtInDocId] until one is. Instagram changes a query's id when it deploys the site, so
 * the app learns the new one on the phone instead of needing a new build.
 */
interface DocIdStore {
    /** The id to send for [query]; always one of the website's shape ([WebGraphQl.isDocId]). */
    suspend fun docId(query: GraphQlQuery): String

    /** The site was seen sending [query] with [docId]: send that from now on. An id not of the website's shape is ignored. */
    suspend fun learned(query: GraphQlQuery, docId: String)
}

/** What a repair saw: the site's own reply for the query and the id it used. toString() prints neither. */
class RepairedQuery(val docId: String, val reply: RawReply) {
    override fun toString() = "RepairedQuery(docId=<${docId.length} chars>, reply=$reply)"
}

/**
 * Learns how the website sends [GraphQlQuery] now, by letting the site's own page send it and watching (spec 2026-10-09 §3.3).
 * Throws when it can't: an [io.github.yuriimurha.reels.instagram.InstagramException.RepairUnavailable] when the repair did not
 * run or saw no query, or what the page's landing says (a login, a challenge, a rate limit).
 */
fun interface QueryRepair {
    suspend fun repair(query: GraphQlQuery): RepairedQuery
}
