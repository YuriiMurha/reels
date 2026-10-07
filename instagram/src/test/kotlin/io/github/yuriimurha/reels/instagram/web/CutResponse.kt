package io.github.yuriimurha.reels.instagram.web

import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect

/**
 * A response whose headers arrive but whose body is cut off, so reading it throws an IOException.
 *
 * The output side is shut down (a FIN, after the bytes already sent) instead of the socket being closed outright: a hard
 * close could, on a loaded machine, reset the connection before the client had parsed the headers, and the call then
 * failed while connecting (a `Transient`) instead of while reading the body. Every test that needs "headers but no
 * body" uses this one helper.
 */
fun cutResponse(code: Int, location: String? = null): MockResponse =
    MockResponse.Builder()
        .code(code)
        .apply { location?.let { addHeader("Location", it) } }
        .body("x".repeat(4096))
        .onResponseBody(SocketEffect.CloseSocket(closeSocket = false, shutdownOutput = true))
        .build()
