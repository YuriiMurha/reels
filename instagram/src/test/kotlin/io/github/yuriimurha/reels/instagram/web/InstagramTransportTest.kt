package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one rule that turns any transport's [RawReply] into a failure or a parseable body (spec 6.4). */
class InstagramTransportTest {
    @Test
    fun aRedirectThatWasNotFollowedIsAChallengeWithNoUrl() {
        val error = assertIs<InstagramException.ChallengeRequired>(classifyReply(RawReply(302, null, null, redirected = true)))
        assertNull(error.challengeUrl)
    }

    @Test
    fun aRateLimitWhoseBodyCouldNotBeReadIsStillRateLimited() {
        assertIs<InstagramException.RateLimited>(classifyReply(RawReply(429, "application/json", null)))
    }

    @Test
    fun anUnreadableBodyOnA2xxOr5xxIsTransient() {
        assertIs<InstagramException.Transient>(classifyReply(RawReply(200, "application/json", null)))
        assertIs<InstagramException.Transient>(classifyReply(RawReply(503, null, null)))
    }

    @Test
    fun anUnreadableBodyOnA4xxIsClassifiedFromTheStatusAlone() {
        assertIs<InstagramException.LoginRequired>(classifyReply(RawReply(403, null, null)))
        assertEquals("http.400.unreadable", assertIs<InstagramException.ShapeChanged>(classifyReply(RawReply(400, null, null))).fieldPath)
    }

    @Test
    fun aJsonChallengeBodyIsAChallenge() {
        val body = """{"message":"challenge_required","challenge":{"url":"/challenge/x/"},"status":"fail"}"""
        val error = assertIs<InstagramException.ChallengeRequired>(classifyReply(RawReply(400, "application/json", body)))
        assertEquals("https://www.instagram.com/challenge/x/", error.challengeUrl)
    }

    @Test
    fun aCleanReplyHasNoFailure() {
        assertNull(classifyReply(RawReply(200, "application/json", """{"status":"ok"}""")))
    }

    @Test
    fun jsonOrThrowReturnsTheObjectOrTheClassifiedFailure() {
        assertEquals("ok", RawReply(200, "application/json", """{"status":"ok"}""").jsonOrThrow().string("status"))
        assertFailsWith<InstagramException.RateLimited> { RawReply(429, null, null).jsonOrThrow() }
        assertFailsWith<InstagramException.ChallengeRequired> { RawReply(301, null, null, redirected = true).jsonOrThrow() }
    }

    @Test
    fun jsonOrThrowRefusesABodyThatIsNotAnObject() {
        assertEquals("$", assertFailsWith<InstagramException.ShapeChanged> { RawReply(200, "application/json", "[1]").jsonOrThrow() }.fieldPath)
    }

    @Test
    fun toStringNeverPrintsTheBody() {
        val secret = "secret-zq5"
        val text = RawReply(200, "application/json", """{"a":"$secret"}""").toString()
        assertFalse(secret in text, text)
        assertEquals("RawReply(code=200, redirected=false, body=<18 chars>)", text)
        assertTrue("<unreadable>" in RawReply(429, null, null).toString())
        assertEquals("RawReply(code=302, redirected=true, body=<unreadable>)", RawReply(302, null, null, redirected = true).toString())
    }
}
