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

    /** What a page of Instagram's own site says about the session by where it is. */
    enum class Landing {
        /** The login page: the session is gone, the owner must log in. */
        LOGIN,

        /** A checkpoint or a suspension: Instagram wants the owner to verify. */
        CHALLENGE,
    }

    /**
     * The [Landing] of a page of Instagram's own origin whose path is [path]: [Landing.LOGIN] under `/accounts/login`,
     * [Landing.CHALLENGE] under `/challenge` or `/accounts/suspended`, null for any other page (the home page, `/explore/`, ...).
     * The caller has already checked that the page is on Instagram's origin.
     */
    fun landingOf(path: String): Landing? = when {
        path.startsWith("/accounts/login") -> Landing.LOGIN
        path.startsWith("/challenge") || path.startsWith("/accounts/suspended") -> Landing.CHALLENGE
        else -> null
    }

    /**
     * The login check: the account edit form the website loads for the logged-in user. The reply holds `form_data.username`;
     * the pk comes from the `ds_user_id` cookie, so this URL carries no id.
     */
    fun currentUser(base: HttpUrl = BASE): HttpUrl = base.newBuilder().addPathSegments("api/v1/accounts/edit/web_form_data/").build()

    /** [url] as an [InstagramTransport] takes it: the encoded path without its leading slash, then `?query` when there is one. */
    fun relative(url: HttpUrl): String = url.encodedPath.removePrefix("/") + (url.encodedQuery?.let { "?$it" } ?: "")

    private val DIGITS = Regex("[0-9]{1,30}")

    /** An id goes into a URL path, so anything but digits ("..", "1/2") is refused before a request is made. */
    private fun pathId(id: String, field: String): String =
        id.takeIf { DIGITS.matches(it) } ?: throw InstagramException.ShapeChanged(field)

    private fun HttpUrl.Builder.cursor(cursor: String?) = apply { cursor?.let { addQueryParameter("max_id", it) } }

    // The collections and their names come from the website's GraphQL query (WebGraphQl.SAVED_COLLECTIONS), not from a URL here.

    fun savedPosts(base: HttpUrl, cursor: String?): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/feed/saved/posts/").cursor(cursor).build()

    fun collectionPosts(base: HttpUrl, collectionId: String, cursor: String?): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/feed/collection").addPathSegment(pathId(collectionId, "collection_id"))
            .addPathSegments("posts/").cursor(cursor).build()

    fun mediaInfo(base: HttpUrl, mediaPk: String): HttpUrl =
        base.newBuilder().addPathSegments("api/v1/media").addPathSegment(pathId(mediaPk, "pk")).addPathSegments("info/").build()
}
