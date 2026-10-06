package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia

/** Decides whether call number [call] (1-based, counting every call) fails instead of answering. */
fun interface FakeFailures {
    fun failureFor(call: Int): InstagramException?
}

/** A fixture-backed [InstagramClient]. Never talks to the network. */
class FakeInstagramClient(
    val library: FakeLibrary = FakeLibrary(),
    private val pageSize: Int = 20,
    override val reportsSavedCollectionIds: Boolean = true,
    var failures: FakeFailures = FakeFailures { null },
) : InstagramClient {
    private val callLog = mutableListOf<String>()

    /** Every call made, in order, e.g. "currentUser", "collections:null", "saved:c3:o:40". */
    val calls: List<String> get() = callLog.toList()

    override suspend fun currentUser(): Account = answer("currentUser") { Account(pk = "1", username = "test_account") }

    override suspend fun collections(cursor: String?): Page<RemoteCollection> =
        answer("collections:$cursor") { library.collections.page(cursor) }

    override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> =
        answer("saved:${collectionId ?: "all"}:$cursor") {
            val items = if (collectionId == null) library.allSaved() else library.itemsIn(collectionId)
            val page = items.page(cursor)
            page.copy(items = page.items.map { visible(it) })
        }

    override suspend fun mediaInfo(mediaPk: String): RemoteMedia =
        answer("mediaInfo:$mediaPk") { visible(library.media(mediaPk) ?: throw InstagramException.ShapeChanged("items[0]")) }

    private inline fun <T> answer(call: String, block: () -> T): T {
        callLog += call
        failures.failureFor(callLog.size)?.let { throw it }
        return block()
    }

    private fun visible(media: RemoteMedia): RemoteMedia =
        if (reportsSavedCollectionIds) media else media.copy(savedCollectionIds = null)

    private fun <T> List<T>.page(cursor: String?): Page<T> {
        val start = (cursor?.removePrefix("o:")?.toInt() ?: 0).coerceAtMost(size)
        val end = minOf(start + pageSize, size)
        return Page(subList(start, end).toList(), if (end < size) "o:$end" else null)
    }
}
