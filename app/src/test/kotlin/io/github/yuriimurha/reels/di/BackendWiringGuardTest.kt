package io.github.yuriimurha.reels.di

import io.github.yuriimurha.reels.testutil.KotlinSource.callArguments
import io.github.yuriimurha.reels.testutil.KotlinSource.code
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source pins (in the style of `FastPolicyGuardTest`) for the two wirings a behaviour test can't see without a network:
 * which HTTP client each half of `Backend.Real` gets, and which Pacer the Sync screen shows.
 */
class BackendWiringGuardTest {
    private fun main(path: String): String {
        val file = File("src/main/kotlin/io/github/yuriimurha/reels/$path")
        assertTrue(file.isFile, "unit tests must run from the app module directory, and $path must exist")
        return code(file.readText())
    }

    /**
     * Instagram's cookies live in the API client's jar. If the CDN fetcher were handed `instagramHttp`, every thumbnail
     * request would carry them to a CDN host, and the CDN's re-send rules (R66) would not apply.
     */
    @Test
    fun theRealFetcherUsesTheCdnClientNeverTheApiClient() {
        val container = main("di/AppContainer.kt")

        val fetchers = callArguments(container, "HttpMediaFetcher(")
        assertEquals(1, fetchers.size, "expected exactly one HttpMediaFetcher in AppContainer: $fetchers")
        assertTrue("cdnHttp" in fetchers.single() && "instagramHttp" !in fetchers.single(), "the fetcher's client: ${fetchers.single()}")

        val apiClients = callArguments(container, "WebInstagramClient(")
        assertEquals(1, apiClients.size)
        assertTrue("instagramHttp" in apiClients.single() && "cdnHttp" !in apiClients.single(), "the API client's client: ${apiClients.single()}")

        val definition = container.lines().single { Regex("""val cdnHttp\b""").containsMatchIn(it) }
        assertTrue("HttpMediaFetcher.client(" in definition || "HttpClientFactory.createCdn(" in definition, "cdnHttp is built by: $definition")
        assertFalse("HttpClientFactory.create(" in definition, "cdnHttp must not be the cookie-carrying API client: $definition")
    }

    @Test
    fun theSyncScreenShowsTheBackendsPacer() {
        val screen = main("ui/sync/SyncScreen.kt")
        val viewModels = callArguments(screen, "SyncViewModel(")
        assertEquals(1, viewModels.size, "expected exactly one SyncViewModel in SyncScreen")
        val arguments = viewModels.single()
        assertTrue("container.backend.pacer" in arguments, "the Sync screen's pacer must be container.backend.pacer: $arguments")
        assertFalse("Pacer(" in arguments.replace("container.backend.pacer", ""), "the screen must not build a Pacer of its own: $arguments")
    }
}
