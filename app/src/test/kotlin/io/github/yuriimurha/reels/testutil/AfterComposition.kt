package io.github.yuriimurha.reels.testutil

import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule

/**
 * [compose] (a compose test rule) wrapped so that [close] runs only after it has disposed the screen. An `@After` runs while the
 * screen is still composed: a database closed there still has the screen's Paging and Room flows querying it ("pool closed").
 * Use the result as the class's one `@get:Rule`, with [compose] a plain field declared before it.
 */
fun closingAfterComposition(compose: TestRule, close: () -> Unit): RuleChain =
    RuleChain.outerRule(
        object : ExternalResource() {
            override fun after() = close()
        },
    ).around(compose)
