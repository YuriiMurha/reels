package io.github.yuriimurha.reels.session

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.SessionProbe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LazySessionProbeTest {
    @Test
    fun buildsTheRealProbeOnFirstUseOnly() = runTest {
        var built = 0
        val probe = LazySessionProbe {
            built++
            object : SessionProbe {
                override suspend fun currentUser() = Account("42", "tester")
            }
        }
        assertEquals(0, built, "constructing the session must not build the HTTP client (it loads WebView)")
        assertEquals(Account("42", "tester"), probe.currentUser())
        probe.currentUser()
        assertEquals(1, built)
    }
}
