package io.github.yuriimurha.reels

import android.app.Application
import android.webkit.WebView
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.di.ReelsWorkerFactory
import io.github.yuriimurha.reels.ui.common.reelsImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReelsApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: lets chrome://inspect show what the real Instagram site requests (spec 6.3). Never in release.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        container = AppContainer(this)
        appScope.launch { container.syncController.recoverInterruptedRuns() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(ReelsWorkerFactory { container }).build()

    /** Coil's one loader for every picture the app shows: a picture rewritten at the same path loads anew (R28's fix round). */
    override fun newImageLoader(context: PlatformContext): ImageLoader = reelsImageLoader(context)
}
