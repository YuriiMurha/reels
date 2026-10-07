package io.github.yuriimurha.reels.di

import android.content.Context
import android.content.Intent
import io.github.yuriimurha.reels.MainActivity

/** Restarts the whole process, so every lazily built dependency in [AppContainer] is rebuilt for the new mode. */
object ProcessRestart {
    /** Ends the process: never call it from a unit test (inject the restart as a lambda instead). */
    fun restart(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        Runtime.getRuntime().exit(0)
    }
}
