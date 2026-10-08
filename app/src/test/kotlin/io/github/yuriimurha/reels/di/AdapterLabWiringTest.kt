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
     * The lab sends through the Instagram transport, which makes its hidden WebView only when a call reaches it. Neither
     * constructing the lab nor having the Developer section on screen may build the transport, let alone a WebView.
     */
    @Test
    fun constructingTheLabDoesNotBuildTheTransport() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        val lab = container.adapterLab
        assertSame(lab, container.adapterLab, "one lab per process")

        assertFalse(container.instagramTransportCreated, "the lab built the transport before any call")
    }
}
