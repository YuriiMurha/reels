package io.github.yuriimurha.reels.transport

/**
 * What the site's own request for [friendlyName] carried and got back: its [docId], and the reply's HTTP status [code]
 * (100-599: a request that got no reply is never reported) and text [body] (null when the page could not read it as text).
 * A caller takes [docId] as learned only from a 2xx reply: an error status says nothing about the id.
 */
class WatchedQuery(val docId: String, val code: Int, val body: String?)

/**
 * A hidden page that shows one of the site's own pages the way a desktop browser does, and watches the site's own request for
 * one GraphQL query. It is how the app learns the query's current `doc_id` when the one it has went stale. Main thread only,
 * like [WebPage]; the real one is [AndroidRepairPage].
 */
interface RepairPage {
    /**
     * Loads [url] in desktop mode and waits (≤ [timeoutMs]) for the site's own POST to /api/graphql named [friendlyName].
     * Throws PageHttpError (429 included) for an HTTP error page, RepairLanding.Login / RepairLanding.Challenge for a
     * login or challenge page, TimeoutCancellationException-free `null` when the request never came.
     *
     * Whichever comes first ends the watch: the watched request's reply (at once, even before the page has finished loading),
     * or one of those failures, which hold for the whole watch: a later main-frame page that is an HTTP error or a login or
     * challenge page ends it too, and at the timeout a page that moved itself to a login or challenge path is that landing. A
     * dead renderer or [destroy] fails it with an `IOException`.
     */
    suspend fun watch(url: String, friendlyName: String, timeoutMs: Long): WatchedQuery?

    /** Releases the page. A [watch] in progress fails at once. */
    fun destroy()
}

/** Where a [RepairPage] landed instead of the page it was sent to: the account needs its owner. */
sealed class RepairLanding : Exception() {
    object Login : RepairLanding()

    object Challenge : RepairLanding()
}
