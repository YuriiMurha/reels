package io.github.yuriimurha.reels.testutil

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A JUnit timeout for Robolectric tests. JUnit's own `Timeout` runs the test on a new thread, and under Robolectric that thread
 * is not the main one: the main looper can no longer be driven, and anything posted to the main thread waits for ever. This one
 * keeps the test where it is and, after [millis], interrupts it: a test parked in `runBlocking` or sleeping then fails with
 * "timed out" instead of hanging the suite.
 */
class MainThreadTimeout(private val millis: Long) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val testThread = Thread.currentThread()
            val timedOut = AtomicBoolean()
            val watchdog = Thread {
                try {
                    Thread.sleep(millis)
                    timedOut.set(true)
                    testThread.interrupt()
                } catch (_: InterruptedException) {
                    // The test ended in time.
                }
            }
            watchdog.isDaemon = true
            watchdog.start()
            var failure: Throwable? = null
            try {
                base.evaluate()
            } catch (t: Throwable) {
                failure = t
            } finally {
                watchdog.interrupt()
                watchdog.join()
                Thread.interrupted() // never leave the interrupt to the next test
            }
            if (timedOut.get()) throw AssertionError("${description.displayName} timed out after $millis ms", failure)
            failure?.let { throw it }
        }
    }
}
