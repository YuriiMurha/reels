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
 */
class WebInstagramClient(
    transport: () -> InstagramTransport,
    private val cookies: CookieStore,
    override val reportsSavedCollectionIds: Boolean = SAVED_COLLECTION_IDS_CONFIRMED,
) : InstagramClient {
    private val transport by lazy(transport)
    private val probe by lazy { WebSessionProbe({ this.transport }, cookies) }

    override suspend fun currentUser(): Account = probe.currentUser()

    /** [url] is built by the caller first, so an invalid id throws before the lazy transport exists. */
    private suspend fun getJson(url: HttpUrl): JsonObject = transport.get(WebEndpoints.relative(url)).jsonOrThrow()

    override suspend fun collections(cursor: String?): Page<RemoteCollection> =
        WebParsers.collectionsPage(getJson(WebEndpoints.collections(WebEndpoints.BASE, cursor)))

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
        /** Spec 6.3 Q2. Flip to true only after the Adapter lab shows saved_collection_ids on saved items (P3). */
        const val SAVED_COLLECTION_IDS_CONFIRMED = false
    }
}
