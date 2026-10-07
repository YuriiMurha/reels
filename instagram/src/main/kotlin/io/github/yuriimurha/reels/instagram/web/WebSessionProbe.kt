package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** "Who is logged in?" over the website's API, using the shared cookie jar. */
class WebSessionProbe(
    private val http: OkHttpClient,
    private val cookies: CookieStore,
    private val base: HttpUrl = WebEndpoints.BASE,
) : SessionProbe {
    override suspend fun currentUser(): Account {
        val userId = cookies.sessionUserId() ?: throw InstagramException.LoginRequired()
        val json = http.getJsonObject(WebEndpoints.currentUser(base, userId))
        val user = json["user"] as? JsonObject ?: throw InstagramException.ShapeChanged("user")
        val username = user.string("username") ?: throw InstagramException.ShapeChanged("user.username")
        val pk = (user["pk"] as? JsonPrimitive)?.contentOrNull ?: userId
        return Account(pk = pk, username = username)
    }
}

/**
 * The logged-in user's id from the `ds_user_id` cookie, or null. The id goes into a request path, so anything but
 * digits ("..", "42a") counts as no session.
 */
internal fun CookieStore.sessionUserId(): String? =
    cookieValue(WebEndpoints.BASE.toString(), "ds_user_id")?.takeIf { id -> id.isNotEmpty() && id.all { it in '0'..'9' } }
