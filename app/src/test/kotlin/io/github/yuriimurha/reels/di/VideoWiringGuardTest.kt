package io.github.yuriimurha.reels.di

import io.github.yuriimurha.reels.testutil.KotlinSource.callArguments
import io.github.yuriimurha.reels.testutil.KotlinSource.code
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source pins (in the style of `BackendWiringGuardTest`) for the video wiring a behaviour test can't reach without a phone. */
class VideoWiringGuardTest {
    private fun main(path: String): String {
        val file = File("src/main/kotlin/io/github/yuriimurha/reels/$path")
        assertTrue(file.isFile, "unit tests must run from the app module directory, and $path must exist")
        return code(file.readText())
    }

    /** The link refresh is an Instagram request: it must go through the one Conservative Pacer, and tell the one session. */
    @Test
    fun theRealResolverUsesTheProcessPacerTheBackendClientAndTheSession() {
        val resolvers = callArguments(main("di/AppContainer.kt"), "RealVideoSourceResolver(")
        assertEquals(1, resolvers.size, "expected exactly one RealVideoSourceResolver in AppContainer: $resolvers")
        val arguments = resolvers.single()
        for (expected in listOf("backend.client", "instagramPacer", "db.mediaDao()", "videoCache", "session")) {
            assertTrue(expected in arguments, "the real resolver must be built with $expected: $arguments")
        }
        assertTrue("Pacer(" !in arguments, "the resolver must not build a Pacer of its own: $arguments")
        // T8: it reaches Instagram only through the backend's client (and so the Pacer's gate), never the transport itself.
        assertTrue(Regex("""\b[iI]nstagramTransport\b""").find(arguments) == null, "the resolver must not get the transport: $arguments")
    }

    /** R76: the link refresh may only run under a valid session; the container's readiness check reads the stored session state. */
    @Test
    fun theRealResolverIsGivenTheSessionReadinessCheck() {
        val arguments = callArguments(main("di/AppContainer.kt"), "RealVideoSourceResolver(").single()
        assertTrue(
            Regex("""isSessionReady\s*=\s*\{[^}]*session\.state\.first\(\)\s+is\s+SessionState\.Valid""").containsMatchIn(arguments),
            "the resolver needs isSessionReady = { session.state.first() is SessionState.Valid }: $arguments",
        )
    }

    @Test
    fun oneVideoCacheForTheProcess() {
        val container = main("di/AppContainer.kt")
        assertEquals(1, callArguments(container, "VideoCache(").size, "SimpleCache refuses a second instance on one directory")
        assertTrue(Regex("""val videoCache\b[^\n]*by lazy""").containsMatchIn(container), "the cache is a lazy singleton")
    }

    /** Spec 8.3: an unsaved item's cached video goes with it, and Delete library empties the cache. */
    @Test
    fun unsavingAndDeleteLibraryEmptyTheVideoCache() {
        val container = main("di/AppContainer.kt")
        val engines = callArguments(container, "SyncEngine(")
        assertEquals(1, engines.size)
        assertTrue("MediaEviction" in engines.single() && "videoCache.remove(" in engines.single(), "the engine's eviction: ${engines.single()}")
        val libraries = callArguments(container, "LibraryRepository(")
        assertEquals(1, libraries.size)
        assertTrue("clearVideoCache" in libraries.single() && "videoCache.clear()" in libraries.single(), "Delete library: ${libraries.single()}")
    }

    /** A video request carries no cookies (spec 8.3): nothing on the video path may touch the jar or Instagram's API client. */
    @Test
    fun theVideoPathNeverTouchesACookieJar() {
        val forbidden = Regex("""CookieHandler|CookieManager|CookieStore|cookieStore|CookieJar|instagramHttp|instagramTransport|InstagramTransport|WebViewTransport|setDefaultRequestProperties|"Cookie"""")
        for (path in listOf("data/media/VideoCache.kt", "data/media/RealVideoSourceResolver.kt", "data/media/VideoSourceResolver.kt", "ui/viewer/ViewerScreen.kt", "ui/viewer/ViewerViewModel.kt", "ui/viewer/ViewerPlayback.kt")) {
            val hit = forbidden.find(main(path))
            assertTrue(hit == null, "$path must not use a cookie jar or the API client: ${hit?.value}")
        }
    }

    /**
     * R85: the policy that turns a refused link into an immediate player error (no retries) is only tested in isolation;
     * only a phone could show the player using it, so the wiring is pinned: the viewer's ExoPlayer gets its media source
     * factory from `videoMediaSourceFactory`, and that factory installs `VideoLoadErrorPolicy` over the cached data source.
     */
    @Test
    fun theViewersPlayerNeverRetriesARefusedLink() {
        val builders = callArguments(main("ui/viewer/ViewerScreen.kt"), "setMediaSourceFactory(")
        assertEquals(1, builders.size, "expected exactly one setMediaSourceFactory in the viewer: $builders")
        assertTrue(builders.single().trim().startsWith("videoMediaSourceFactory("), "the viewer's media source factory: ${builders.single()}")
        assertTrue("DefaultMediaSourceFactory(" !in main("ui/viewer/ViewerScreen.kt"), "the viewer must not build a factory of its own")

        val factory = Regex("""fun videoMediaSourceFactory\([^)]*\)[^=]*=([^\n]*\n){1,3}""").find(main("data/media/VideoCache.kt"))?.value
        assertTrue(factory != null, "videoMediaSourceFactory must be defined in VideoCache.kt")
        assertTrue("cachedDataSourceFactory(" in factory, "it reads through the cache: $factory")
        assertTrue(Regex("""setLoadErrorHandlingPolicy\(\s*VideoLoadErrorPolicy\(\)\s*\)""").containsMatchIn(factory), "and never retries an HTTP error: $factory")
    }

    /**
     * The viewer draws video on a TextureView. With the default SurfaceView the surface of a page the owner had swiped
     * away from and back to often never arrived (emulator: 6 of 8 tries failed, 0 of 8 with a TextureView), and the clip
     * then played as sound under the thumbnail. Only a phone can show it, so the choice is pinned here.
     */
    @Test
    fun theViewerDrawsVideoOnATextureView() {
        val frames = callArguments(main("ui/viewer/ViewerScreen.kt"), "ContentFrame(")
        assertEquals(1, frames.size, "expected exactly one ContentFrame in the viewer: $frames")
        assertTrue("SURFACE_TYPE_TEXTURE_VIEW" in frames.single(), "the viewer's ContentFrame must use a TextureView: ${frames.single()}")
    }
}
