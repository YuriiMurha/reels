package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** "Who is logged in?" over the website's API, using the shared cookie jar. */
class WebSessionProbe(
    private val http: OkHttpClient,
    private val cookies: CookieStore,
    private val base: HttpUrl = WebEndpoints.BASE,
) : SessionProbe {
    override suspend fun currentUser(): Account {
        val userId = cookies.cookieValue(WebEndpoints.BASE.toString(), "ds_user_id") ?: throw InstagramException.LoginRequired()
        val json = http.getJsonObject(WebEndpoints.currentUser(base, userId))
        val user = json["user"] as? JsonObject ?: throw InstagramException.ShapeChanged("user")
        val username = user.string("username") ?: throw InstagramException.ShapeChanged("user.username")
        val pk = (user["pk"] as? JsonPrimitive)?.content ?: userId
        return Account(pk = pk, username = username)
    }
}
