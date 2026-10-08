package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.serialization.json.JsonObject

/**
 * One Instagram API call: a GET, or a POST of the website's own GraphQL query. Implementations make exactly one request per
 * call and never retry or follow redirects.
 */
interface InstagramTransport {
    /** [pathAndQuery] is relative to https://www.instagram.com/ (no leading slash), built only by [WebEndpoints]. */
    suspend fun get(pathAndQuery: String): RawReply

    /**
     * One POST of the website's own GraphQL [query] with [docId] and [variables] (JSON text). The same rules as [get]: one
     * request, never retried, never redirected. Tokens are the transport's business and never leave it.
     */
    suspend fun graphql(query: GraphQlQuery, docId: String, variables: String): RawReply
}

/**
 * One reply. [body] is null when it could not be read. [redirected] is true for a redirect that was not followed (its
 * target is unknown to a browser fetch). toString() never prints the body.
 */
class RawReply(val code: Int, val contentType: String?, val body: String?, val redirected: Boolean = false) {
    override fun toString() = "RawReply(code=$code, redirected=$redirected, body=${body?.let { "<${it.length} chars>" } ?: "<unreadable>"})"
}

/** The failure [reply] signals, or null when it is safe to parse (spec 6.4, one rule for every transport). */
fun classifyReply(reply: RawReply): InstagramException? = when {
    reply.redirected -> InstagramException.ChallengeRequired(null)
    reply.body == null -> if (reply.code in 200..299 || reply.code >= 500) {
        InstagramException.Transient()
    } else {
        classifyUnreadable(reply.code, null, reply.contentType)
    }
    else -> ErrorClassifier.classify(reply.code, null, reply.contentType, reply.body)
}

/** The reply's JSON object, or the InstagramException it signals. */
internal fun RawReply.jsonOrThrow(): JsonObject {
    classifyReply(this)?.let { throw it }
    return parseObject(body!!) ?: throw InstagramException.ShapeChanged("$")
}
