package io.github.yuriimurha.reels.instagram.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The line any transport logs for a non-2xx reply (the OkHttp interceptor's own cases are in ErrorReplyLoggerTest). */
class ErrorReplySummaryTest {
    @Test
    fun aJsonObjectShowsItsAllowlistedFieldsAndItsKeys() {
        assertEquals(
            "<-- 429 reply: status=fail message=\"Please wait a few minutes before you try again.\" require_login=true " +
                "keys=[message, require_login, status]",
            ErrorReplySummary.of(
                429,
                "application/json",
                """{"message":"Please wait a few minutes before you try again.","require_login":true,"status":"fail"}""",
            ),
        )
    }

    @Test
    fun aValueThatFailsTheLabFilterIsRedactedToItsLength() {
        val message = "user john.doe 1234567 not found"
        assertEquals(
            "<-- 400 reply: message=<redacted len ${message.length}> keys=[message]",
            ErrorReplySummary.of(400, "application/json", """{"message":"$message"}"""),
        )
    }

    @Test
    fun aBodyThatIsNotAJsonObjectShowsOnlyItsKindAndSize() {
        val html = "<html>secret-zq3</html>"
        val line = ErrorReplySummary.of(403, "text/html; charset=utf-8", html)
        assertEquals("<-- 403 reply: non-JSON, text/html, ${html.length} bytes", line)
        assertFalse("secret-zq3" in line)
        assertEquals("<-- 400 reply: non-JSON, application/json, 5 bytes", ErrorReplySummary.of(400, "application/json", "[1,2]"))
        assertEquals("<-- 500 reply: non-JSON, no content-type, 0 bytes", ErrorReplySummary.of(500, null, ""))
    }

    @Test
    fun theSizeIsInUtf8BytesUnlessTheCallerSaysAndACutBodyGetsAPlus() {
        assertEquals("<-- 400 reply: non-JSON, text/plain, 2 bytes", ErrorReplySummary.of(400, "text/plain", "é"))
        assertEquals("<-- 400 reply: non-JSON, text/plain, 7+ bytes", ErrorReplySummary.of(400, "text/plain", "x", cut = true, byteCount = 7))
    }

    @Test
    fun aBodyThatCouldNotBeReadIsSaidSo() {
        assertEquals("<-- 429 reply: unreadable", ErrorReplySummary.of(429, "application/json", null))
    }
}
