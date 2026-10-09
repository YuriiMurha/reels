package io.github.yuriimurha.reels.transport

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A local server that answers by path and remembers every request it got. The hidden-page tests ([AndroidWebPageTest],
 * [AndroidRepairPageTest]) load nothing else: its origin is `http://127.0.0.1:<port>`, never Instagram.
 */
internal class LocalSite : AutoCloseable {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                // Unknown paths (the page's favicon, say) are plain 404s.
                return routes[request.url.encodedPath]?.invoke(request) ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val origin: String get() = "http://127.0.0.1:${server.port}"

    fun route(path: String, handler: (RecordedRequest) -> MockResponse) {
        routes[path] = handler
    }

    fun requestsTo(path: String): List<RecordedRequest> = seen.filter { it.url.encodedPath == path }

    fun requests(): List<RecordedRequest> = seen.toList()

    override fun close() = server.close()
}
