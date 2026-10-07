package io.github.yuriimurha.reels.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import io.github.yuriimurha.reels.R

object SyncNotifications {
    private const val CHANNEL_ID = "sync"
    private const val NOTIFICATION_ID = 1

    fun foregroundInfo(context: Context): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setOngoing(true)
            .setProgress(0, 0, true)
            // User-started work: show at once instead of after Android's 10 s foreground-service notification delay.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
