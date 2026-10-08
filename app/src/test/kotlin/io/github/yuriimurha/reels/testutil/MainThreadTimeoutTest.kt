package io.github.yuriimurha.reels.testutil

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.runner.Description
import org.junit.runners.model.Statement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MainThreadTimeoutTest {
    private fun statement(body: () -> Unit) = object : Statement() {
        override fun evaluate() = body()
    }

    @Test
    fun aTestThatHangsFailsOnItsOwnThreadInsteadOfHanging() {
        val testThread = Thread.currentThread()
        var ranOn: Thread? = null
        val hanging = statement {
            ranOn = Thread.currentThread()
            runBlocking { CompletableDeferred<Unit>().await() } // waits for something that never comes
        }

        val error = assertFailsWith<AssertionError> { MainThreadTimeout(200).apply(hanging, Description.EMPTY).evaluate() }

        assertTrue("timed out after 200 ms" in error.message.orEmpty(), error.message)
        assertSame(testThread, ranOn, "the test ran on its own thread, not a new one")
        assertFalse(Thread.currentThread().isInterrupted, "no interrupt is left for the next test")
    }

    @Test
    fun aTestThatEndsInTimePassesOrFailsAsItself() {
        MainThreadTimeout(10_000).apply(statement { }, Description.EMPTY).evaluate()

        val own = IllegalStateException("its own failure")
        assertSame(own, assertFailsWith<IllegalStateException> { MainThreadTimeout(10_000).apply(statement { throw own }, Description.EMPTY).evaluate() })
        assertEquals(false, Thread.currentThread().isInterrupted)
    }
}
