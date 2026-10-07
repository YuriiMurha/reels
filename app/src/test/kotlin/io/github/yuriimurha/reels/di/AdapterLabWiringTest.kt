package io.github.yuriimurha.reels.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

@RunWith(AndroidJUnit4::class)
class AdapterLabWiringTest {
    /**
     * The HTTP client's user agent comes from the WebView provider, which must not be loaded just because the lab was
     * constructed (or because the Developer section is on screen). Only a lab call that reaches the network builds it.
     */
    @Test
    fun constructingTheLabDoesNotBuildTheHttpClient() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        val lab = container.adapterLab
        assertSame(lab, container.adapterLab, "one lab per process")

        val delegate = AppContainer::class.java.getDeclaredField("instagramHttp\$delegate")
            .apply { isAccessible = true }
            .get(container) as Lazy<*>
        assertFalse(delegate.isInitialized(), "the lab built the HTTP client before any call")
    }
}
