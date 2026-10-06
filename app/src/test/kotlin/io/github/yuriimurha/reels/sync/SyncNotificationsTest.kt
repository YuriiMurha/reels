package io.github.yuriimurha.reels.sync

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SyncNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun showsTheSyncNotificationAtOnce() {
        val info = SyncNotifications.foregroundInfo(context)
        // Without this Android holds a foreground-service notification back for ~10 s, so a short run never shows it.
        // The platform has no public getter for the behaviour, only the builder setter.
        val field = Notification::class.java.getDeclaredField("mFgsDeferBehavior").apply { isAccessible = true }
        val behavior = field.getInt(info.notification)
        assertEquals(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE, behavior)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }
}
