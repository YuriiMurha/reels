package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * The real adapter (spec 4.2) over Instagram's web API, sharing the WebView's cookie jar. One request per call, no
 * retries: the caller paces every call through the Pacer.
 */
class WebInstagramClient(
    http: () -> OkHttpClient,
    private val cookies: CookieStore,
    override val reportsSavedCollectionIds: Boolean = SAVED_COLLECTION_IDS_CONFIRMED,
    private val base: HttpUrl = WebEndpoints.BASE,
) : InstagramClient {
    private val http by lazy(http)
    private val probe by lazy { WebSessionProbe(this.http, cookies, base) }

    override suspend fun currentUser(): Account = probe.currentUser()

    override suspend fun collections(cursor: String?): Page<RemoteCollection> =
        WebParsers.collectionsPage(http.getJsonObject(WebEndpoints.collections(base, cursor)))

    override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> {
        val url = if (collectionId == null) {
            WebEndpoints.savedPosts(base, cursor)
        } else {
            WebEndpoints.collectionPosts(base, collectionId, cursor)
        }
        return WebParsers.savedPage(http.getJsonObject(url))
    }

    /** Null when Instagram no longer has the item (P8). A challenge, login or rate limit still throws. */
    override suspend fun mediaInfo(mediaPk: String): RemoteMedia? {
        // Built first: an invalid pk throws before the lazy client exists.
        val url = WebEndpoints.mediaInfo(base, mediaPk)
        return try {
            WebParsers.mediaInfo(http.getJsonObject(url))
        } catch (e: InstagramException.ShapeChanged) {
            if (e.fieldPath == "http.400" || e.fieldPath == "http.404") null else throw e
        }
    }

    companion object {
        /** Spec 6.3 Q2. Flip to true only after the Adapter lab shows saved_collection_ids on saved items (P3). */
        const val SAVED_COLLECTION_IDS_CONFIRMED = false
    }
}
