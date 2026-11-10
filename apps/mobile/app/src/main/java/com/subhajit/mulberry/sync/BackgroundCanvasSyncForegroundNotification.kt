package com.subhajit.mulberry.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.subhajit.mulberry.MainActivity
import com.subhajit.mulberry.R
import com.subhajit.mulberry.notifications.MulberryNotificationChannels

object BackgroundCanvasSyncForegroundNotification {
    private const val NOTIFICATION_ID = 2201

    // Only Android 11 and older run expedited work as a foreground service, and they need no
    // service type, so the app declares no foreground service permission.
    fun createForegroundInfo(context: Context): ForegroundInfo {
        MulberryNotificationChannels.registerAll(context)
        return ForegroundInfo(NOTIFICATION_ID, createNotification(context))
    }

    private fun createNotification(context: Context) = NotificationCompat.Builder(
        context,
        MulberryNotificationChannels.CHANNEL_ID_CANVAS_SYNC
    )
        .setSmallIcon(R.drawable.brand_iconmark_white)
        .setContentTitle("Syncing wallpaper canvas…")
        .setContentText("Updating your Mulberry live wallpaper.")
        .setContentIntent(createLaunchIntent(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createLaunchIntent(context: Context): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
