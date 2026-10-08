package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.lab.LabRules
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Debug builds log one redacted line per non-2xx Instagram reply (what the owner pastes into a Claude session after a
 * cooldown). Every value is fake; nothing here is a real handle, id or session.
 */
class ErrorReplyLoggerTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

    private fun debugClient(lines: MutableList<String>): OkHttpClient =
        HttpClientFactory.create(InMemoryCookieStore(), "UA", logger = { lines += it })

    /** Sends one GET and reads the whole body, as a caller would. */
    private fun fetch(client: OkHttpClient, path: String = "/api/v1/x/"): Pair<Int, String> =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.code to it.body.string() }

    private fun replies(lines: List<String>): List<String> = lines.filter { it.startsWith("<-- ") && " reply:" in it }

    /** Runs one error body through a debug client and returns the single reply line it logged. */
    private fun replyTo(code: Int, body: String): String {
        server.enqueue(json(code, body))
        val lines = CopyOnWriteArrayList<String>()
        fetch(debugClient(lines))
        return replies(lines).single()
    }

    // --- what the line says ---

    @Test
    fun aRateLimitedLoginBounceShowsItsStatusMessageFlagAndKeys() {
        val line = replyTo(401, """{"message":"Please wait a few minutes before you try again.","require_login":true,"status":"fail"}""")
        assertEquals(
            "<-- 401 reply: status=fail message=\"Please wait a few minutes before you try again.\" require_login=true " +
                "keys=[message, require_login, status]",
            line,
        )
    }

    @Test
    fun aUserAgentMismatchShowsItsMessage() {
        val line = replyTo(400, """{"message":"useragent mismatch","status":"fail"}""")
        assertEquals("<-- 400 reply: status=fail message=\"useragent mismatch\" keys=[message, status]", line)
    }

    @Test
    fun errorTypeSpamAndLockAreShownInOrderAndAbsentFieldsAreLeftOut() {
        val line = replyTo(400, """{"status":"fail","lock":false,"spam":true,"error_type":"generic_request_error"}""")
        assertEquals(
            "<-- 400 reply: status=fail error_type=\"generic_request_error\" spam=true lock=false keys=[error_type, lock, spam, status]",
            line,
        )
    }

    @Test
    fun aBodyWithNoKnownFieldStillShowsItsKeys() {
        assertEquals("<-- 400 reply: keys=[errors, ok]", replyTo(400, """{"ok":1,"errors":[]}"""))
    }

    @Test
    fun aFieldOfTheWrongTypeOrNullIsMarkedNotShown() {
        val line = replyTo(400, """{"message":{"a":1},"error_type":null,"require_login":"true","spam":null}""")
        assertEquals(
            "<-- 400 reply: message=<other type> error_type=null require_login=<other type> spam=null " +
                "keys=[error_type, message, require_login, spam]",
            line,
        )
    }

    // --- what it never says ---

    @Test
    fun aChallengeShowsItsMessageAndTheUrlAppearsNowhere() {
        val url = "https://www.instagram.com/challenge/x/"
        server.enqueue(
            json(
                400,
                """{"message":"challenge_required","challenge":{"url":"$url"},"checkpoint_url":"$url","status":"fail"}""",
            ),
        )
        val lines = CopyOnWriteArrayList<String>()
        fetch(debugClient(lines))

        assertEquals(
            "<-- 400 reply: status=fail message=\"challenge_required\" keys=[challenge, checkpoint_url, message, status]",
            replies(lines).single(),
        )
        assertTrue(lines.none { "/challenge/x" in it || "https://" in it.substringAfter("reply:", "") }, "a URL leaked: $lines")
    }

    @Test
    fun aMessageWithAHandleOrALongDigitRunIsRedactedToItsLength() {
        val message = "user john.doe 1234567 not found"
        server.enqueue(json(400, """{"message":"$message","status":"fail"}"""))
        val lines = CopyOnWriteArrayList<String>()
        fetch(debugClient(lines))

        assertEquals("<-- 400 reply: status=fail message=<redacted len ${message.length}> keys=[message, status]", replies(lines).single())
        assertEquals(31, message.length)
        assertTrue(lines.none { "john.doe" in it || "1234567" in it }, "the message leaked: $lines")
    }

    @Test
    fun aMessageWithASessionWordOrANewlineIsRedacted() {
        val worded = "your " + "session" + "id" + " is bad"
        assertEquals(
            "<-- 400 reply: message=<redacted len ${worded.length}> keys=[message]",
            replyTo(400, """{"message":"$worded"}"""),
        )
        assertEquals(
            "<-- 400 reply: message=<redacted len 17> keys=[message]",
            replyTo(400, """{"message":"line one\nline two"}"""),
        )
    }

    @Test
    fun aStatusThatFailsTheFilterIsRedactedToo() {
        assertEquals("<-- 400 reply: status=<redacted len 12> keys=[status]", replyTo(400, """{"status":"fail 1234567"}"""))
    }

    @Test
    fun valuesUnderOtherKeysAndOddKeyNamesNeverAppear() {
        val lines = CopyOnWriteArrayList<String>()
        server.enqueue(
            json(
                400,
                """{"status":"fail","user":{"pk":"98765432101","username":"jane_doe"},"checkpoint_url":"https://www.instagram.com/challenge/action/NONCEzz1/",""" +
                    """"jane.doe@example.test":"v","Odd Key":1,"message":"ok"}""",
            ),
        )
        fetch(debugClient(lines))

        val line = replies(lines).single()
        assertEquals("<-- 400 reply: status=fail message=\"ok\" keys=[checkpoint_url, message, status, user, +2 other]", line)
        for (secret in listOf("98765432101", "jane_doe", "NONCEzz1", "jane.doe", "Odd Key", "example.test")) {
            assertTrue(lines.none { secret in it }, "$secret leaked into the log")
        }
    }

    @Test
    fun aBodyThatIsNotJsonLogsOnlyItsKindAndSize() {
        val html = "<!doctype html><title>Log in</title><p>secret-zq4</p>"
        server.enqueue(
            MockResponse.Builder().code(403).addHeader("Content-Type", "text/html; charset=utf-8").body(html).build(),
        )
        val lines = CopyOnWriteArrayList<String>()
        fetch(debugClient(lines))

        assertEquals("<-- 403 reply: non-JSON, text/html, ${html.length} bytes", replies(lines).single())
        assertTrue(lines.none { "secret-zq4" in it })
    }

    @Test
    fun aJsonArrayOrAnEmptyRedirectIsNotAnObject() {
        assertEquals("<-- 400 reply: non-JSON, application/json, 5 bytes", replyTo(400, "[1,2]"))

        val marker = "NONCEzz2"
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://www.instagram.com/challenge/action/$marker/").build())
        val lines = CopyOnWriteArrayList<String>()
        fetch(debugClient(lines))
        val line = replies(lines).single()
        assertTrue(line.startsWith("<-- 302 reply: non-JSON, ") && line.endsWith(", 0 bytes"), line)
        assertTrue("Location: ██" in lines, "the Location header stays redacted: $lines")
        assertTrue(lines.none { marker in it }, "the redirect target leaked: $lines")
    }

    // --- what it never touches ---

    @Test
    fun aSuccessLogsNoReplyLineAndItsBodyIsStillWholeForTheCaller() {
        val body = """{"items":[1,2,3],"status":"ok"}"""
        server.enqueue(json(200, body))
        val lines = CopyOnWriteArrayList<String>()

        val (code, read) = fetch(debugClient(lines))

        assertEquals(200, code)
        assertEquals(body, read, "peeking must not consume the body")
        assertTrue(lines.isNotEmpty(), "the header log is still there")
        assertTrue(replies(lines).isEmpty(), "a 2xx must not log a reply line: $lines")
        assertTrue(lines.none { "items" in it }, "a 2xx body must not be logged: $lines")
    }

    @Test
    fun aBodyLongerThanThePeekLimitReachesTheCallerWholeAndIsCountedAsCut() {
        val body = "x".repeat(40_000)
        server.enqueue(MockResponse.Builder().code(400).addHeader("Content-Type", "text/html").body(body).build())
        val lines = CopyOnWriteArrayList<String>()

        val (_, read) = fetch(debugClient(lines))

        assertEquals(body, read, "peeking must not consume the body")
        assertEquals("<-- 400 reply: non-JSON, text/html, ${ErrorReplyLogger.PEEK_LIMIT}+ bytes", replies(lines).single())
    }

    @Test
    fun aGzippedReplyIsReadAsTheCallerSeesIt() = runTest {
        val text = """{"message":"useragent mismatch","status":"fail"}"""
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()
        server.enqueue(
            MockResponse.Builder().code(400).addHeader("Content-Type", "application/json").addHeader("Content-Encoding", "gzip")
                .body(Buffer().write(zipped)).build(),
        )
        val lines = CopyOnWriteArrayList<String>()

        val error = assertFailsWith<InstagramException.ShapeChanged> { debugClient(lines).getJsonObject(server.url("/api/v1/x/")) }

        assertEquals("http.400", error.fieldPath, "the caller still gets the un-zipped body")
        assertEquals("<-- 400 reply: status=fail message=\"useragent mismatch\" keys=[message, status]", replies(lines).single())
    }

    @Test
    fun theLineFollowsTheHeaderBlockOfItsResponse() {
        server.enqueue(json(401, """{"status":"fail"}"""))
        val lines = CopyOnWriteArrayList<String>()
        fetch(debugClient(lines))

        val end = lines.indexOf("<-- END HTTP")
        val reply = lines.indexOfFirst { " reply:" in it }
        assertTrue(end >= 0 && reply > end, "reply line must follow '<-- END HTTP': $lines")
        assertEquals(lines.size - 1, reply, "and be the last line of the request: $lines")
    }

    @Test
    fun aCutBodyStillGivesTheCallerItsClassificationAndThisLoggerSaysSo() = runTest {
        server.enqueue(cutResponse(429))
        val lines = CopyOnWriteArrayList<String>()

        assertFailsWith<InstagramException.RateLimited> { debugClient(lines).getJsonObject(server.url("/api/v1/x/")) }

        assertEquals("<-- 429 reply: unreadable", replies(lines).single())
        assertTrue(lines.none { "xxxx" in it }, "the body leaked: $lines")
    }

    // --- the response the caller gets does not change ---

    @Test
    fun theCallerGetsTheSameExceptionWithAndWithoutTheLogger() = runTest {
        val cases = listOf(
            401 to """{"message":"Please wait a few minutes before you try again.","require_login":true,"status":"fail"}""",
            400 to """{"message":"useragent mismatch","status":"fail"}""",
            400 to """{"message":"challenge_required","challenge":{"url":"https://www.instagram.com/challenge/x/"},"status":"fail"}""",
            403 to """{"message":"login_required","status":"fail"}""",
            500 to """{"status":"fail"}""",
            400 to "<html>nope</html>",
        )
        for ((code, body) in cases) {
            server.enqueue(json(code, body))
            val plain = assertFailsWith<InstagramException> {
                HttpClientFactory.create(InMemoryCookieStore(), "UA").getJsonObject(server.url("/api/v1/x/"))
            }
            server.enqueue(json(code, body))
            val lines = CopyOnWriteArrayList<String>()
            val debug = assertFailsWith<InstagramException> { debugClient(lines).getJsonObject(server.url("/api/v1/x/")) }

            assertEquals(plain::class, debug::class, "$code $body")
            assertEquals(plain.message, debug.message, "$code $body")
            assertEquals((plain as? InstagramException.ShapeChanged)?.fieldPath, (debug as? InstagramException.ShapeChanged)?.fieldPath)
            assertEquals(1, replies(lines).size, "one reply line for $code")
        }
    }

    @Test
    fun theKnownCaseKeepsItsClassification() = runTest {
        server.enqueue(json(401, """{"message":"Please wait a few minutes before you try again.","require_login":true,"status":"fail"}"""))
        assertFailsWith<InstagramException.RateLimited> { debugClient(CopyOnWriteArrayList()).getJsonObject(server.url("/api/v1/x/")) }
        server.enqueue(json(400, """{"message":"challenge_required","status":"fail"}"""))
        assertFailsWith<InstagramException.ChallengeRequired> { debugClient(CopyOnWriteArrayList()).getJsonObject(server.url("/api/v1/x/")) }
        server.enqueue(json(400, """{"message":"useragent mismatch","status":"fail"}"""))
        assertEquals("http.400", assertFailsWith<InstagramException.ShapeChanged> { debugClient(CopyOnWriteArrayList()).getJsonObject(server.url("/api/v1/x/")) }.fieldPath)
    }

    // --- only debug builds ---

    @Test
    fun noInterceptorIsAddedWithoutALogger() {
        val release = HttpClientFactory.create(InMemoryCookieStore(), "UA", logger = null)
        assertEquals(2, release.networkInterceptors.size, "SessionGuard and noRetryAfterOn503 only: ${release.networkInterceptors}")
        assertTrue(release.networkInterceptors.none { it is ErrorReplyLogger || it is HttpLoggingInterceptor })
        assertTrue(release.interceptors.none { it is ErrorReplyLogger })
    }

    @Test
    fun withALoggerTheReplyLoggerIsOneMoreNetworkInterceptorBeforeTheHeaderLog() {
        val debug = HttpClientFactory.create(InMemoryCookieStore(), "UA") { }
        assertEquals(4, debug.networkInterceptors.size, "${debug.networkInterceptors}")
        assertEquals(1, debug.networkInterceptors.count { it is ErrorReplyLogger })
        assertEquals(1, debug.networkInterceptors.count { it is HttpLoggingInterceptor })
        assertTrue(
            debug.networkInterceptors.indexOfFirst { it is ErrorReplyLogger } < debug.networkInterceptors.indexOfFirst { it is HttpLoggingInterceptor },
            "outside the header log, so its line comes after the response's headers",
        )
        assertTrue(debug.interceptors.none { it is ErrorReplyLogger }, "a network interceptor, not an application one")
    }

    @Test
    fun theCdnClientIsNotGivenOne() {
        assertTrue(HttpClientFactory.createCdn("UA").networkInterceptors.none { it is ErrorReplyLogger })
    }

    // --- the allowlist stays inside the lab's safe-value rules ---

    @Test
    fun everyFieldItCanShowIsOneThatTheLabAlreadyTreatsAsReadable() {
        val shown = ErrorReplyLogger.STRING_FIELDS + ErrorReplyLogger.BOOLEAN_FIELDS
        assertEquals(6, shown.size)
        val missing = shown.filter { it !in LabRules.VISIBLE_VALUE_KEYS }
        assertTrue(missing.isEmpty(), "not in LabRules.VISIBLE_VALUE_KEYS: $missing")
    }
}
