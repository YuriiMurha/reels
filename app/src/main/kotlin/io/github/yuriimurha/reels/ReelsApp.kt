package io.github.yuriimurha.reels

import android.app.Application
import androidx.work.Configuration
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.di.ReelsWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReelsApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { container.syncController.recoverInterruptedRuns() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(ReelsWorkerFactory { container }).build()
}
