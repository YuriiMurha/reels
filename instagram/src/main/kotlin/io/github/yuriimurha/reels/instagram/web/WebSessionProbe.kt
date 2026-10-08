package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import kotlinx.serialization.json.JsonObject

/**
 * "Who is logged in?" over the website's API. The id is the `ds_user_id` cookie's; the account edit form says the name.
 * [transport] is only asked once there is a session, so a logged-out probe builds nothing and sends nothing.
 */
class WebSessionProbe(
    private val transport: () -> InstagramTransport,
    private val cookies: CookieStore,
) : SessionProbe {
    override suspend fun currentUser(): Account {
        val userId = cookies.sessionUserId() ?: throw InstagramException.LoginRequired()
        val json = transport().get(WebEndpoints.relative(WebEndpoints.currentUser())).jsonOrThrow()
        val form = json["form_data"] as? JsonObject ?: throw InstagramException.ShapeChanged("form_data")
        val username = form.string("username") ?: throw InstagramException.ShapeChanged("form_data.username")
        return Account(pk = userId, username = username)
    }
}

/**
 * The logged-in user's id from the `ds_user_id` cookie, or null. Anything but digits ("..", "42a") counts as no session.
 */
internal fun CookieStore.sessionUserId(): String? =
    cookieValue(WebEndpoints.BASE.toString(), "ds_user_id")?.takeIf { id -> id.isNotEmpty() && id.all { it in '0'..'9' } }
