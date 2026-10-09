package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

/**
 * The real adapter (spec 4.2) over Instagram's web API, through an [InstagramTransport]. One request per call, no
 * retries: the caller paces every call through the Pacer. The transport is built lazily, on the first call that needs it.
 *
 * The collections come from the website's own GraphQL query ([WebGraphQl.SAVED_COLLECTIONS], spec 2026-10-09 §3.1), sent with
 * the doc id [docIds] holds. When Instagram no longer runs that id, [collections] throws [InstagramException.StaleQuery], and
 * only [repairCollections] (through [repair]) learns the current one.
 */
class WebInstagramClient(
    transport: () -> InstagramTransport,
    private val cookies: CookieStore,
    private val docIds: DocIdStore,
    private val repair: QueryRepair,
    override val reportsSavedCollectionIds: Boolean = SAVED_COLLECTION_IDS_CONFIRMED,
) : InstagramClient {
    private val transport by lazy(transport)
    private val probe by lazy { WebSessionProbe({ this.transport }, cookies) }

    override suspend fun currentUser(): Account = probe.currentUser()

    /** [url] is built by the caller first, so an invalid id throws before the lazy transport exists. */
    private suspend fun getJson(url: HttpUrl): JsonObject = transport.get(WebEndpoints.relative(url)).jsonOrThrow()

    /**
     * A page whose next cursor is the one it was asked with did not advance: fact CURSOR (the `after` variable) is unverified,
     * and a server that ignores it would answer the same page forever. That is a shape change after one wasted request, never
     * a loop of the same POST until the run budget refuses.
     */
    override suspend fun collections(cursor: String?): Page<RemoteCollection> {
        val query = WebGraphQl.SAVED_COLLECTIONS
        // Read first: a store that can't answer throws before the lazy transport exists.
        val docId = docIds.docId(query)
        val reply = transport.graphql(query, docId, WebGraphQl.savedCollectionsVariables(cursor))
        val page = WebParsers.collectionsGraphQl(WebParsers.savedCollectionsJsonOrThrow(reply))
        if (page.nextCursor != null && page.nextCursor == cursor) throw InstagramException.ShapeChanged("page_info.end_cursor")
        return page
    }

    /** No request of its own: the repair's page sent the query. The id is learned only once its reply parsed as a page. */
    override suspend fun repairCollections(onReplyFailure: (InstagramException) -> Unit): Page<RemoteCollection> {
        val query = WebGraphQl.SAVED_COLLECTIONS
        val repaired = repair.repair(query)
        val page = try {
            WebParsers.collectionsGraphQl(WebParsers.savedCollectionsJsonOrThrow(repaired.reply))
        } catch (e: InstagramException) {
            onReplyFailure(e)
            throw e
        }
        docIds.learned(query, repaired.docId)
        return page
    }

    override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> {
        val url = if (collectionId == null) {
            WebEndpoints.savedPosts(WebEndpoints.BASE, cursor)
        } else {
            WebEndpoints.collectionPosts(WebEndpoints.BASE, collectionId, cursor)
        }
        return WebParsers.savedPage(getJson(url))
    }

    /** Null when Instagram no longer has the item (P8). A challenge, login or rate limit still throws. */
    override suspend fun mediaInfo(mediaPk: String): RemoteMedia? {
        // Built first: an invalid pk throws before the lazy transport exists.
        val url = WebEndpoints.mediaInfo(WebEndpoints.BASE, mediaPk)
        return try {
            WebParsers.mediaInfo(getJson(url))
        } catch (e: InstagramException.ShapeChanged) {
            if (e.fieldPath == "http.400" || e.fieldPath == "http.404") null else throw e
        }
    }

    companion object {
        /**
         * Spec 6.3 Q2, spike Q2 answered yes on 2026-10-09: every saved item lists its collections in `saved_collection_ids`,
         * so sync walks All Saved once and takes the memberships from it (strategy A); the per-collection feeds are not walked.
         */
        const val SAVED_COLLECTION_IDS_CONFIRMED = true
    }
}
