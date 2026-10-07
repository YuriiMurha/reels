package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Every Instagram URL and host the rest of the app needs. Nothing outside `:instagram` spells one out. */
object WebEndpoints {
    /** Instagram's home page. A challenge without a usable URL redirects from here to the checkpoint, and loading it gives the WebView a csrftoken. */
    const val HOME_URL = "https://www.instagram.com/"

    /** Instagram's own login page, where the login WebView starts. */
    const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

    val BASE: HttpUrl = HOME_URL.toHttpUrl()

    /**
     * The login flow may only visit https pages on Instagram's own domains and on Facebook and Meta, which its login and
     * verification use.
     */
    val LOGIN_DOMAINS: List<String> = listOf("instagram.com", "facebook.com", "meta.com")

    /**
     * True for an https page on one of [LOGIN_DOMAINS] or a subdomain of it. The match has a dot boundary, so
     * `evilinstagram.com` and `instagram.com.evil.example` are out; scheme and host are compared without regard to case.
     */
    fun isLoginPage(scheme: String?, host: String?): Boolean {
        if (!scheme.equals("https", ignoreCase = true) || host == null) return false
        val lower = host.lowercase()
        return LOGIN_DOMAINS.any { lower == it || lower.endsWith(".$it") }
    }

    /** Candidate from spec 6.2, confirmed on the phone in Task 18. */
    fun currentUser(base: HttpUrl, userId: String): HttpUrl =
        base.newBuilder()
            .addPathSegments("api/v1/users")
            .addPathSegment(userId)
            .addPathSegment("info")
            .addPathSegment("")
            .build()

    private val DIGITS = Regex("[0-9]{1,30}")

    /** An id goes into a URL path, so anything but digits ("..", "1/2") is refused before a request is made. */
    private fun pathId(id: String, field: String): String =
        id.takeIf { DIGITS.matches(it) } ?: throw InstagramException.ShapeChanged(field)

    private fun HttpUrl.Builder.cursor(cursor: String?) = apply { cursor?.let { addQueryParameter("max_id", it) } }

    /** Candidate from spec 6.2 (P4): the collection types are the ones instagrapi sends. */
    fun collections(base: HttpUrl, cursor: String?): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/collections/list/")
            .addQueryParameter("collection_types", "[\"ALL_MEDIA_AUTO_COLLECTION\",\"MEDIA\",\"AUDIO_AUTO_COLLECTION\"]")
            .cursor(cursor).build()

    fun savedPosts(base: HttpUrl, cursor: String?): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/feed/saved/posts/").cursor(cursor).build()

    fun collectionPosts(base: HttpUrl, collectionId: String, cursor: String?): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/feed/collection").addPathSegment(pathId(collectionId, "collection_id"))
            .addPathSegments("posts/").cursor(cursor).build()

    fun mediaInfo(base: HttpUrl, mediaPk: String): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/media").addPathSegment(pathId(mediaPk, "pk")).addPathSegments("info/").build()
}
