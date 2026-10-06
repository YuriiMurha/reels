package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException.ChallengeRequired
import io.github.yuriimurha.reels.instagram.InstagramException.LoginRequired
import io.github.yuriimurha.reels.instagram.InstagramException.RateLimited
import io.github.yuriimurha.reels.instagram.InstagramException.ShapeChanged
import io.github.yuriimurha.reels.instagram.InstagramException.Transient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** One case per row of spec 6.4. */
class ErrorClassifierTest {
    private fun classify(code: Int, body: String = "{}", location: String? = null, contentType: String? = "application/json") =
        ErrorClassifier.classify(code, location, contentType, body)

    @Test
    fun checkpointInTheBody() {
        val error = assertIs<ChallengeRequired>(
            classify(400, """{"message":"checkpoint_required","checkpoint_url":"https://www.instagram.com/challenge/x/","status":"fail"}"""),
        )
        assertEquals("https://www.instagram.com/challenge/x/", error.challengeUrl)
    }

    @Test
    fun challengeObjectInTheBody() {
        val error = assertIs<ChallengeRequired>(
            classify(400, """{"message":"challenge_required","challenge":{"url":"https://i.instagram.com/challenge/y/"},"status":"fail"}"""),
        )
        assertEquals("https://i.instagram.com/challenge/y/", error.challengeUrl)
    }

    @Test
    fun redirectToAChallengeBecomesAbsolute() {
        val error = assertIs<ChallengeRequired>(classify(302, body = "", location = "/challenge/?next=/"))
        assertEquals("https://www.instagram.com/challenge/?next=/", error.challengeUrl)
    }

    @Test
    fun redirectToLogin() {
        assertIs<LoginRequired>(classify(302, body = "", location = "https://www.instagram.com/accounts/login/?next=/api/"))
    }

    @Test
    fun unknownRedirectStopsLikeAChallenge() {
        assertIs<ChallengeRequired>(classify(302, body = "", location = "https://www.instagram.com/accounts/suspended/"))
    }

    @Test
    fun loginRequiredVariants() {
        assertIs<LoginRequired>(classify(401))
        assertIs<LoginRequired>(classify(403, """{"message":"login_required"}"""))
        assertIs<LoginRequired>(classify(400, """{"require_login":true}"""))
    }

    @Test
    fun rateLimitVariants() {
        assertIs<RateLimited>(classify(429, body = ""))
        assertIs<RateLimited>(classify(400, """{"message":"feedback_required"}"""))
        assertIs<RateLimited>(classify(400, """{"message":"Please wait a few minutes before you try again."}"""))
    }

    @Test
    fun serverErrorsAreTransient() {
        assertIs<Transient>(classify(500, body = ""))
        assertIs<Transient>(classify(503, body = "<html></html>", contentType = "text/html"))
    }

    @Test
    fun unknownClientErrorsNeedRepair() {
        assertEquals("http.404", assertIs<ShapeChanged>(classify(404)).fieldPath)
    }

    @Test
    fun htmlInsteadOfJsonMeansLoggedOut() {
        assertIs<LoginRequired>(classify(200, "<!DOCTYPE html><html></html>", contentType = "text/html; charset=utf-8"))
    }

    @Test
    fun nonJsonBodyNeedsRepair() {
        assertEquals("$", assertIs<ShapeChanged>(classify(200, "nope", contentType = "text/plain")).fieldPath)
    }

    @Test
    fun okJsonPasses() {
        assertNull(classify(200, """{"status":"ok"}"""))
    }

    // --- R34: precedence is challenge, then rate limit, then login ---

    @Test
    fun challengeBeatsAServerErrorStatus() {
        assertIs<ChallengeRequired>(classify(500, """{"message":"checkpoint_required","status":"fail"}"""))
    }

    @Test
    fun challengeBeatsTooManyRequests() {
        assertIs<ChallengeRequired>(classify(429, """{"message":"challenge_required","status":"fail"}"""))
    }

    @Test
    fun rateLimitBeatsLoginRequired() {
        assertIs<RateLimited>(
            classify(401, """{"message":"Please wait a few minutes before you try again.","require_login":true,"status":"fail"}"""),
        )
    }

    @Test
    fun rateLimitBeatsAServerErrorStatus() {
        assertIs<RateLimited>(classify(503, """{"message":"feedback_required","status":"fail"}"""))
    }

    // --- R38a: only a challenge OBJECT counts ---

    @Test
    fun aNullOrBooleanChallengeFieldIsNotAChallenge() {
        assertNull(classify(200, """{"challenge":null,"status":"ok"}"""))
        assertNull(classify(200, """{"challenge":false,"status":"ok"}"""))
    }

    @Test
    fun anEmptyChallengeObjectStillStopsWithoutAUrl() {
        assertNull(assertIs<ChallengeRequired>(classify(400, """{"challenge":{},"status":"fail"}""")).challengeUrl)
    }

    // --- R38b: challenge URLs are resolved and must stay on instagram.com over https ---

    @Test
    fun protocolRelativeChallengeUrlResolvesToHttps() {
        val error = assertIs<ChallengeRequired>(
            classify(400, """{"message":"challenge_required","challenge":{"url":"//i.instagram.com/challenge/x/"}}"""),
        )
        assertEquals("https://i.instagram.com/challenge/x/", error.challengeUrl)
    }

    @Test
    fun foreignChallengeUrlIsDroppedButStillStops() {
        val fromBody = assertIs<ChallengeRequired>(
            classify(400, """{"message":"challenge_required","challenge":{"url":"https://evil.example/challenge/"}}"""),
        )
        assertNull(fromBody.challengeUrl)
        val fromRedirect = assertIs<ChallengeRequired>(classify(302, body = "", location = "https://evil.example/challenge/"))
        assertNull(fromRedirect.challengeUrl)
    }

    @Test
    fun lookAlikeAndPlainHttpChallengeUrlsAreDropped() {
        for (url in listOf(
            "https://notinstagram.com/challenge/",
            "https://www.instagram.com.evil.example/challenge/",
            "https://www.instagram.com@evil.example/challenge/",
            "http://www.instagram.com/challenge/",
            "javascript:alert(1)",
        )) {
            val error = assertIs<ChallengeRequired>(classify(302, body = "", location = url), url)
            assertNull(error.challengeUrl, url)
        }
    }

    @Test
    fun theBareDomainAndItsSubdomainsAreAccepted() {
        assertEquals(
            "https://instagram.com/challenge/",
            assertIs<ChallengeRequired>(classify(302, body = "", location = "https://instagram.com/challenge/")).challengeUrl,
        )
    }

    // --- R38d: the HTML check ignores case ---

    @Test
    fun htmlContentTypeIsMatchedCaseInsensitively() {
        assertIs<LoginRequired>(classify(200, "<!DOCTYPE html><html></html>", contentType = "Text/HTML"))
    }
}
