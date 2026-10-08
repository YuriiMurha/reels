package io.github.yuriimurha.reels.instagram.web

import okhttp3.Interceptor
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Debug builds only (`HttpClientFactory.create` adds it when a logger is passed). For every response that is not 2xx it
 * logs ONE line that says what Instagram answered, in a form that is safe to paste. The line itself, an allowlist of
 * fields with every value filtered, is [ErrorReplySummary]'s (the format is the same whichever transport got the reply);
 * this interceptor only gets the body to it. A 2xx is never touched.
 *
 * It peeks at most [PEEK_LIMIT] bytes ([Response.peekBody]), so the body the caller reads is whole and the log costs
 * nothing on the wire. A network interceptor sees the body as it came off the wire, still gzipped when OkHttp asked for
 * gzip by itself (it does, and un-zips only after the network interceptors), so the peek is un-zipped here. Whatever goes
 * wrong while looking (a body cut off mid-read, a stream that is not gzip) becomes `reply: unreadable`: this logger never
 * changes what the caller gets.
 *
 * Registered BEFORE the HttpLoggingInterceptor, so its line comes after that response's header block.
 */
internal class ErrorReplyLogger(private val log: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code !in 200..299) log(describe(response))
        return response
    }

    private fun describe(response: Response): String =
        try {
            val peeked = response.peekBody(PEEK_LIMIT)
            val wire = peeked.bytes()
            val cut = wire.size >= PEEK_LIMIT
            val gzipped = response.header("Content-Encoding")?.trim().equals("gzip", ignoreCase = true)
            val bytes = if (gzipped) gunzip(wire) else wire
            ErrorReplySummary.of(response.code, response.header("Content-Type"), bytes.toString(Charsets.UTF_8), cut, bytes.size)
        } catch (e: Exception) {
            ErrorReplySummary.of(response.code, null, null)
        }

    /** Un-zips what it can: a stream cut off at the peek limit keeps its decoded start; one that is not gzip yields nothing. */
    private fun gunzip(wire: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            GZIPInputStream(ByteArrayInputStream(wire)).use { input ->
                val buffer = ByteArray(4096)
                while (out.size() < UNZIPPED_LIMIT) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
        } catch (_: IOException) {
            // Keep what was decoded so far.
        }
        return out.toByteArray()
    }

    companion object {
        const val PEEK_LIMIT: Long = 16L * 1024
        private const val UNZIPPED_LIMIT = 64 * 1024
    }
}
