package io.github.yuriimurha.reels.instagram.web

/**
 * The Set-Cookie values for the two cookies that make up a session, as they are written into the shared jar when the
 * owner pastes a sessionid (spec D3) and when a failed paste is rolled back. The attributes mirror what Instagram itself
 * sets, so the cookie behaves like one issued by a login in the WebView.
 */
object WebSessionCookies {
    /** The URL the jar keys Instagram's cookies by (no trailing slash). */
    const val ORIGIN = "https://www.instagram.com"

    /** `sessionid`: HttpOnly, a year. A null [value] expires it, which is how a rollback removes what a paste added. */
    fun sessionCookie(value: String?): String =
        if (value == null) {
            "sessionid=; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=0"
        } else {
            "sessionid=$value; Domain=.instagram.com; Path=/; Secure; HttpOnly; Max-Age=31536000"
        }

    /** `ds_user_id`: readable by script, ninety days. A null [value] expires it. */
    fun userCookie(value: String?): String =
        if (value == null) {
            "ds_user_id=; Domain=.instagram.com; Path=/; Secure; Max-Age=0"
        } else {
            "ds_user_id=$value; Domain=.instagram.com; Path=/; Secure; Max-Age=7776000"
        }
}
