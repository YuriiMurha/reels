package io.github.yuriimurha.reels.instagram.web

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object WebEndpoints {
    val BASE: HttpUrl = "https://www.instagram.com/".toHttpUrl()

    /** Candidate from spec 6.2, confirmed on the phone in Task 18. */
    fun currentUser(base: HttpUrl, userId: String): HttpUrl =
        base.newBuilder()
            .addPathSegments("api/v1/users")
            .addPathSegment(userId)
            .addPathSegment("info")
            .addPathSegment("")
            .build()
}
