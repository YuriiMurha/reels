package io.github.yuriimurha.reels.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AndroidCookieStoreTest {
    @Test
    fun logoutAlsoClearsWhatTheWebViewKeptInItsStorage() {
        var cleared = 0
        AndroidCookieStore(clearWebStorage = { cleared++ }).clearAll()
        assertEquals(1, cleared, "localStorage and friends can hold Instagram state that outlives the cookies")
    }

    @Test
    fun theRealStorageClearingRunsWithoutAWebViewInstance() {
        AndroidCookieStore().clearAll()
    }
}
