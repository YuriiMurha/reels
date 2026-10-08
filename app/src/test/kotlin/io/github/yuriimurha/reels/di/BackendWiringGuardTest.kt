package io.github.yuriimurha.reels.di

import io.github.yuriimurha.reels.testutil.KotlinSource.callArguments
import io.github.yuriimurha.reels.testutil.KotlinSource.code
import io.github.yuriimurha.reels.testutil.KotlinSource.skeleton
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

    /** Every Kotlin file of `:app`'s `src/main`, comments blanked, by its path under the package root. */
    private fun allMain(): Map<String, String> {
        val root = File("src/main/kotlin/io/github/yuriimurha/reels")
        assertTrue(root.isDirectory, "unit tests must run from the app module directory")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.invariantSeparatorsPath.substringAfter("reels/") to code(it.readText()) }
    }

    /**
     * Instagram's cookies live in the browser's jar. The CDN fetcher is handed `cdnHttp` and never the transport, so a
     * thumbnail request can never go through the Instagram page or carry its cookies, and the CDN's re-send rules (R66) apply.
     */
    @Test
    fun theRealFetcherUsesTheCdnClientNeverTheInstagramTransport() {
        val container = main("di/AppContainer.kt")

        val fetchers = callArguments(container, "HttpMediaFetcher(")
        assertEquals(1, fetchers.size, "expected exactly one HttpMediaFetcher in AppContainer: $fetchers")
        assertTrue("cdnHttp" in fetchers.single() && "instagramTransport" !in fetchers.single(), "the fetcher's client: ${fetchers.single()}")

        val definition = container.lines().single { Regex("""val cdnHttp\b""").containsMatchIn(it) }
        assertTrue("HttpMediaFetcher.client(" in definition || "HttpClientFactory.createCdn(" in definition, "cdnHttp is built by: $definition")
    }

    /** The Real client, the session probe and the Adapter lab all send through the one WebView transport (R91's page, not OkHttp). */
    @Test
    fun theRealClientTheProbeAndTheLabAllGetTheWebViewTransport() {
        val container = main("di/AppContainer.kt")
        for (call in listOf("WebInstagramClient(", "WebSessionProbe(", "AdapterLab(")) {
            val arguments = callArguments(container, call)
            assertEquals(1, arguments.size, "expected exactly one $call in AppContainer: $arguments")
            assertTrue(
                Regex("""^\s*\{\s*instagramTransport\s*}""").containsMatchIn(arguments.single()) && "cdnHttp" !in arguments.single(),
                "$call must get { instagramTransport }: ${arguments.single()}",
            )
        }
    }

    /** No OkHttp API path is left in production: the API client factory and the JVM-test transport are `:instagram`'s, for tests. */
    @Test
    fun noOkHttpPathToInstagramsApiRemainsInProduction() {
        val sources = allMain()
        assertTrue(sources.size > 50, "the scan found ${sources.size} files")
        val forbidden = listOf(
            "OkHttpTransport" to Regex("""\bOkHttpTransport\b"""),
            "HttpClientFactory.create(" to Regex("""\bHttpClientFactory\s*\.\s*create\s*\("""),
            "instagramHttp" to Regex("""\binstagramHttp\b"""),
        )
        for ((path, text) in sources) {
            for ((what, pattern) in forbidden) {
                assertFalse(pattern.containsMatchIn(text), "$path must not use $what (only createCdn is allowed in production)")
            }
        }
    }

    /**
     * Every class, object (named, companion or anonymous) and interface of [sources] that lists `InstagramTransport` among its
     * supertypes, as `path: <keyword> <name>`. The header runs from the keyword to the `{` that opens the body (or the end of the
     * declaration's line when it has none); what is inside the primary constructor's parentheses is a parameter, not a supertype.
     */
    private fun declaredTransports(sources: Map<String, String>): List<String> {
        val found = mutableListOf<String>()
        for ((path, text) in sources) {
            val shape = skeleton(text)
            for (keyword in Regex("""(?<![:\w])(?:class|object|interface)\b""").findAll(shape)) {
                var depth = 0
                var end = keyword.range.last + 1
                while (end < shape.length) {
                    val c = shape[end]
                    if (c == '(') depth++
                    if (c == ')') depth--
                    if (depth == 0 && c == '{') break
                    // A header goes on over a line that ends in `:` or `,`, and over one whose next non-blank line starts with `:` or `,`
                    // (the colon of the supertypes on its own line, after the constructor's `)`); any other end of line stops it.
                    if (depth == 0 && c == '\n' &&
                        shape.substring(keyword.range.first, end).trimEnd().lastOrNull() !in listOf(':', ',') &&
                        shape.substring(end).trimStart().firstOrNull() !in listOf(':', ',')
                    ) break
                    end++
                }
                val header = StringBuilder()
                var open = 0
                for (c in shape.substring(keyword.range.first, end)) {
                    if (c == '(') open++
                    if (open == 0) header.append(c) else header.append(' ')
                    if (c == ')') open--
                }
                if (Regex(""":[^{]*\bInstagramTransport\b""").containsMatchIn(header)) {
                    found += "$path: " + (Regex("""^(?:class|object|interface)(?:\s+\w+)?""").find(header)?.value ?: header.toString().trim())
                }
            }
        }
        return found
    }

    /**
     * The one thing that sends Instagram's API requests is the WebView transport. A second implementation (an OkHttp one, a
     * wrapper that retries or fans out) would be a path around the Pacer's one fetch per call and the page's own TLS and headers.
     */
    @Test
    fun theOnlyImplementationOfTheTransportIsTheWebViewTransport() {
        val sources = allMain()
        assertEquals(listOf("transport/WebViewTransport.kt: class WebViewTransport"), declaredTransports(sources))
        // The scanner itself: each form of declaration it must see (the real tree has just the one above).
        val forms = mapOf(
            "named class" to "class Sneaky(val x: Int = 0) : InstagramTransport {",
            "named object" to "private object Sneaky : InstagramTransport {",
            "anonymous object" to "val t = object : InstagramTransport {",
            "second supertype" to "class Sneaky : Base(), InstagramTransport {",
            "interface extending it" to "interface Sneaky : InstagramTransport {",
            "delegation" to "class Sneaky(private val inner: InstagramTransport) : InstagramTransport by inner",
            "supertype on the next line" to "class Sneaky :\n    InstagramTransport {",
            "colon on the next line" to "class Sneaky\n    : InstagramTransport {",
            "colon on the line after the constructor" to "class Sneaky(\n    private val x: Int,\n)\n    : InstagramTransport {",
        )
        for ((what, text) in forms) assertEquals(1, declaredTransports(mapOf("a.kt" to text)).size, "the scan must see $what: $text")
        // What only mentions the type is not a declaration of one.
        for (text in listOf(
            "class Holder(private val transport: InstagramTransport) {", "fun make(): InstagramTransport {", "val t: InstagramTransport = x",
            "class A(val f: () -> InstagramTransport)", "val k = WebViewTransport::class",
        )) {
            assertEquals(emptyList(), declaredTransports(mapOf("a.kt" to text)), text)
        }
    }

    /**
     * The real client, the session probe and the Adapter lab are the three users of the transport, and they are built in one place,
     * so each gets `{ instagramTransport }` there and nowhere else is a second client, probe or lab (with another transport, or
     * none of the container's hooks) created.
     */
    @Test
    fun theClientTheProbeAndTheLabAreConstructedOnlyByTheContainer() {
        val sources = allMain()
        assertTrue(sources.size > 50, "the scan found ${sources.size} files")
        for (name in listOf("WebInstagramClient", "WebSessionProbe", "AdapterLab")) {
            val construction = Regex("""(?<!class )(?<!\w)$name\s*\(|::\s*$name\b""")
            val users = sources.mapValues { (_, text) -> text.lines().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n") }
                .filterValues { construction.containsMatchIn(it) }.keys
            assertEquals(setOf("di/AppContainer.kt"), users, "$name is constructed only by the container")
        }
    }

    /** The WebView behind the transport is made in one place, so Mock mode and the guards above can reason about when it exists. */
    @Test
    fun androidWebPageIsConstructedOnlyByTheContainer() {
        val construction = Regex("""(?<!class )(?<!\w)AndroidWebPage\s*\(|::\s*AndroidWebPage\b""")
        val users = allMain().filterValues { construction.containsMatchIn(it) }.keys
        assertEquals(setOf("di/AppContainer.kt"), users)
        assertEquals(1, construction.findAll(allMain().getValue("di/AppContainer.kt")).count(), "exactly one page factory")
    }

    /**
     * Every hook that tells the transport about the session goes through the container's two guarded functions, which only act
     * on a transport that already exists: logout, a paste and Delete library never build a WebView, and neither does Mock mode.
     */
    @Test
    fun theSessionHooksGoThroughTheGuardedContainerFunctions() {
        val container = main("di/AppContainer.kt")
        val session = callArguments(container, "SessionRepository(").single()
        assertTrue(Regex("""beforeSessionChange\s*=\s*::resetInstagramTransport\b""").containsMatchIn(session), "the session's reset: $session")
        assertTrue(Regex("""beforeCheck\s*=\s*::allowNewInstagramAttempts\b""").containsMatchIn(session), "the session's check hook: $session")
        val library = callArguments(container, "LibraryRepository(").single()
        assertTrue(Regex("""beforeSessionChange\s*=\s*::resetInstagramTransport\b""").containsMatchIn(library), "Delete library's reset: $library")
        val lab = callArguments(main("ui/lab/AdapterLabScreen.kt"), "AdapterLabViewModel(").single()
        assertTrue(Regex("""beforeCall\s*=\s*container::allowNewInstagramAttempts\b""").containsMatchIn(lab), "the lab's hook: $lab")

        for (guarded in listOf("fun resetInstagramTransport", "fun allowNewInstagramAttempts")) {
            val body = container.substringAfter(guarded).substringBefore("\n    }")
            assertTrue("instagramTransportLazy.isInitialized()" in body, "$guarded must only act on a transport that exists: $body")
        }
        assertTrue(Regex("""instagramTransportLazy:\s*Lazy<WebViewTransport>\s*=\s*lazy\s*\{""").containsMatchIn(container), "the transport stays a Lazy that can be asked")
    }

    /** A reset that fails is logged by the two repositories that swallow it, in debug builds only: the one sink is `null` in a release build. */
    @Test
    fun theResetFailureLogGoesThroughTheDebugOnlySink() {
        val container = main("di/AppContainer.kt")
        val definition = container.lines().single { Regex("""val debugLog\b""").containsMatchIn(it) }
        assertTrue(
            Regex("""=\s*if\s*\(\s*BuildConfig\.DEBUG\s*\)\s*\{[^}]*Log\.d\(\s*"InstagramHttp"[^}]*}\s*else\s+null\b""").containsMatchIn(definition),
            "the sink: $definition",
        )
        for (call in listOf("SessionRepository(", "LibraryRepository(")) {
            val arguments = callArguments(container, call).single()
            assertTrue(Regex("""debugLog\s*=\s*debugLog\b""").containsMatchIn(arguments), "$call needs the debug-only sink: $arguments")
        }
    }

    /**
     * `cancel()` only asks WorkManager to cancel; the Mock mode switch must not restart before WorkManager has recorded it
     * (R67), and under the test WorkManager's synchronous executors the two are indistinguishable at runtime. The cancel
     * itself and the backend kind stamp are run, not read, in `ContainerSyncWiringTest`.
     */
    @Test
    fun theMockSwitchWaitsForTheCancelToBeRecorded() {
        val switches = callArguments(main("di/AppContainer.kt"), "MockModeSwitch(")
        assertEquals(1, switches.size, "expected exactly one MockModeSwitch in AppContainer: $switches")
        assertTrue("syncScheduler.cancelAndAwait()" in switches.single(), "cancelSync must await the cancel: ${switches.single()}")
    }

    /**
     * R82: the real engine asks `SessionRepository.runSession` (Valid under the run's own epoch, lock-free: pinned by
     * `SessionRepositoryTest`) before every request; the fake one has no session. Read, not run: running the real engine in a
     * test is exactly the request to Instagram that must never happen.
     */
    @Test
    fun theRealEngineIsGatedOnTheSessionAndTheFakeOneIsNot() {
        val container = main("di/AppContainer.kt")
        val engines = callArguments(container, "SyncEngine(")
        assertEquals(1, engines.size, "expected exactly one SyncEngine in AppContainer: $engines")
        assertTrue(Regex("""sessionUsable\s*=\s*sessionUsable\b""").containsMatchIn(engines.single()), "the engine's gate: ${engines.single()}")
        val real = Regex("""is Backend\.Real\s*->\s*\{([^}]*)}""").find(container)?.groupValues?.get(1)
        assertTrue(real != null && Regex("""sessionUsable\s*=\s*session::runSession\b""").containsMatchIn(real), "Backend.Real's gate: $real")
        // Each run is a new user action for the transport's page limit: the real engine says so, the fake one has no transport.
        assertTrue(real != null && Regex("""beforeRun\s*=\s*::allowNewInstagramAttempts\b""").containsMatchIn(real), "Backend.Real's run hook: $real")
        assertTrue(Regex("""beforeRun\s*=\s*beforeRun\b""").containsMatchIn(engines.single()), "the engine's run hook: ${engines.single()}")
        val fake = Regex("""is Backend\.Fake\s*->\s*\{([^}]*\{[^}]*}[^}]*)}""").find(container)?.groupValues?.get(1)
        assertTrue(fake != null && Regex("""sessionUsable\s*=\s*\{\s*RunSession\.USABLE\s*}""").containsMatchIn(fake), "Backend.Fake's gate: $fake")
        assertTrue(fake != null && "allowNewInstagramAttempts" !in fake, "Mock mode's engine must not touch the transport: $fake")
    }

    /** R84: the engine checks the account against the container's one per-library store, the same one Delete library clears. */
    @Test
    fun theEngineChecksTheLibrarysOwnAccount() {
        val container = main("di/AppContainer.kt")
        val engine = callArguments(container, "SyncEngine(").single()
        assertTrue(Regex("""libraryAccount\s*=\s*libraryAccount\b""").containsMatchIn(engine), "the engine's account: $engine")
        val definition = container.lines().single { Regex("""val libraryAccount\b""").containsMatchIn(it) }
        assertTrue(Regex("""StoredLibraryAccount\(\s*settings\s*,\s*SyncWorker\.kindOf\(usesFake\)\s*\)""").containsMatchIn(definition), definition)
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

    /**
     * H2: Mock mode's Sync screen shows the REAL Pacer's cooldown and 24 h count as one extra line, because Check now, the lab
     * and the video resolver use `instagramPacer` there. With the real backend the screen's own pacer already is that one, so
     * nothing extra is passed (and the lines would be shown twice). Read-only `status()` calls: no request.
     */
    @Test
    fun theSyncScreenAddsTheRealPacerExactlyWhenTheBackendIsFake() {
        val arguments = callArguments(main("ui/sync/SyncScreen.kt"), "SyncViewModel(").single()
        assertTrue(
            Regex("""realPacer\s*=\s*if\s*\(\s*container\.backend\s+is\s+Backend\.Fake\s*\)\s*container\.instagramPacer\s+else\s+null\b""")
                .containsMatchIn(arguments),
            "the Sync screen must pass realPacer = if (container.backend is Backend.Fake) container.instagramPacer else null: $arguments",
        )
    }

    /**
     * Mock mode (the fake library) syncs without a session; the real backend must not start without a valid one. The
     * ViewModel's behaviour is tested with the flag passed in; this pins that the screen derives it from the backend.
     */
    @Test
    fun theSyncScreenRequiresASessionExactlyWhenTheBackendIsReal() {
        val arguments = callArguments(main("ui/sync/SyncScreen.kt"), "SyncViewModel(").single()
        assertTrue(
            Regex("""requiresSession\s*=\s*container\.backend\s+is\s+Backend\.Real\b""").containsMatchIn(arguments),
            "the Sync screen must pass requiresSession = container.backend is Backend.Real: $arguments",
        )
    }
}
