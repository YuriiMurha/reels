package io.github.yuriimurha.reels.data.settings

import io.github.yuriimurha.reels.instagram.web.DocIdStore
import io.github.yuriimurha.reels.instagram.web.GraphQlQuery
import io.github.yuriimurha.reels.instagram.web.WebGraphQl

/**
 * The doc ids of the website's GraphQL queries, kept in [settings] under each query's friendly name (spec 2026-10-09 §3.3):
 * the one learned last, or the query's built-in id until one is, or after it is cleared. R8: only an id of the website's shape
 * ([WebGraphQl.isDocId]) is ever answered or stored, since both transports refuse any other before sending.
 */
class SettingsDocIdStore(private val settings: SettingsStore) : DocIdStore {
    override suspend fun docId(query: GraphQlQuery): String =
        settings.graphqlDocId(query.friendlyName)?.takeIf(WebGraphQl::isDocId) ?: query.builtInDocId

    /** An id not of the website's shape is ignored: the one in use stays. */
    override suspend fun learned(query: GraphQlQuery, docId: String) {
        if (WebGraphQl.isDocId(docId)) settings.setGraphqlDocId(query.friendlyName, docId)
    }
}
