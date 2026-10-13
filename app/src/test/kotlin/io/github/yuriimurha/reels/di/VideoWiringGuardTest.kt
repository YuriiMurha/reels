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
            Regex("""isSessionReady\s*=\s*\{\s*session\.state\.first\(\)\s+is\s+SessionState\.Valid\s*}""").containsMatchIn(arguments),
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

    /**
     * A video request carries no cookies (spec 8.3), and neither does a carousel image (spec 2026-10-12 §4): nothing on the
     * video or carousel path may touch the jar or Instagram's API client. Its one request goes through the client it is given.
     */
    @Test
    fun theVideoPathNeverTouchesACookieJar() {
        val forbidden = Regex("""CookieHandler|CookieManager|CookieStore|cookieStore|CookieJar|instagramHttp|instagramTransport|InstagramTransport|WebViewTransport|setDefaultRequestProperties|"Cookie"""")
        for (path in listOf(
            "data/media/VideoCache.kt", "data/media/RealVideoSourceResolver.kt", "data/media/VideoSourceResolver.kt", "ui/viewer/ViewerScreen.kt",
            "ui/viewer/ViewerViewModel.kt", "ui/viewer/ViewerPlayback.kt", "ui/viewer/CarouselPager.kt",
            "data/media/PacedMediaInfo.kt", "data/media/CarouselRepository.kt", "data/media/CarouselImageStore.kt",
            "data/media/VideoStarts.kt",
        )) {
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
     * R26: the viewer's one player is built with `viewerLoadControl` (1 s to start, 2 s after a stall; `ViewerLoadControlTest`
     * reads its values). No second player is built anywhere.
     */
    @Test
    fun theViewersPlayerStartsOnTheViewersLoadControl() {
        val screen = main("ui/viewer/ViewerScreen.kt")
        val controls = callArguments(screen, "setLoadControl(")
        assertEquals(listOf("viewerLoadControl()"), controls.map { it.trim() }, "the viewer's player's load control")
        assertEquals(1, Regex("""ExoPlayer\.Builder\(""").findAll(screen).count(), "one player, built where it always was")

        val control = Regex("""fun viewerLoadControl\(\)[^=]*=([^\n]*\n){1,8}""").find(main("data/media/VideoCache.kt"))?.value
        assertTrue(control != null, "viewerLoadControl must be defined in VideoCache.kt")
        val durations = callArguments(control, "setBufferDurationsMsForStreaming(").single()
        assertTrue("START_BUFFER_MS" in durations && "RESTART_BUFFER_MS" in durations, "the streaming durations: $durations")
        assertTrue("setBufferDurationsMs(" !in control, "the local playback values stay Media3's: $control")
    }

    /**
     * R26: a carousel's video starts are written through the player's own cookieless stack: the container gives the carousel
     * repository a [VideoStarts] over the one video cache, built by `videoStartDataSourceFactory` with the player's user agent,
     * and that factory is `cachedDataSourceFactory`, the player's, with only its one-try upstream added.
     */
    @Test
    fun videoStartsAreWrittenThroughThePlayersOwnStack() {
        val container = main("di/AppContainer.kt")
        val repository = callArguments(container, "CarouselRepository(").single()
        assertTrue(Regex("""\bstarts\s*=\s*videoStarts\b""").containsMatchIn(repository), "the repository writes starts: $repository")
        val starts = callArguments(container, "CacheVideoStarts(").single()
        assertTrue("videoCache.cache" in starts, "over the one video cache: $starts")
        val stack = callArguments(starts, "videoStartDataSourceFactory(").single()
        assertTrue("videoRequestUserAgent" in stack && "videoCache.cache" in stack, "with the player's cache and user agent: $stack")
        // M5: each write's own abort reaches its upstream, or cancelling a write could not close its request.
        assertTrue(Regex("""dataSource\s*=\s*\{\s*abort\s*->""").containsMatchIn(starts), "the write's abort is taken: $starts")
        assertTrue(Regex(""",\s*abort\s*$""").containsMatchIn(stack.trim()), "and handed to the stack: $stack")
        // N1: Mock mode never loads the WebView for a user agent, for the player or a start.
        assertTrue(
            Regex("""val videoRequestUserAgent: String\? get\(\) = if \(usesFake\) null else videoUserAgent""").containsMatchIn(container),
            "Mock mode's video requests use no WebView user agent",
        )
        assertTrue(
            "container.videoRequestUserAgent" in callArguments(main("ui/viewer/ViewerScreen.kt"), "videoMediaSourceFactory(").single(),
            "the player reads the same user agent",
        )

        val factory = Regex("""fun videoStartDataSourceFactory\([^)]*\)[^=]*=([^\n]*\n){1,3}""").find(main("data/media/VideoCache.kt"))?.value
        assertTrue(factory != null, "videoStartDataSourceFactory must be defined in VideoCache.kt")
        assertTrue(
            Regex("""cachedDataSourceFactory\(\s*context\s*,\s*cache\s*,\s*userAgent\s*,\s*onUpstream\s*=\s*\{\s*OneTryUpstream\.Factory\(\s*it\s*,\s*abort\s*\)\s*}\s*\)""").containsMatchIn(factory),
            "the player's stack plus one try: $factory",
        )
        // I1: a cache error fails the start; the player's FLAG_IGNORE_CACHE_ON_ERROR would stream past a failing cache.
        assertTrue(Regex("""\)\s*\.setFlags\(0\)""").containsMatchIn(factory), "no ignoring a cache error: $factory")
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
        // A carousel's video item draws through that same frame (VideoFrame), never a ContentFrame of its own.
        assertTrue("ContentFrame(" !in main("ui/viewer/CarouselPager.kt"), "the carousel must draw video through VideoFrame")
        assertTrue("VideoFrame(" in main("ui/viewer/CarouselPager.kt"), "the carousel's video item uses VideoFrame")
    }
}
