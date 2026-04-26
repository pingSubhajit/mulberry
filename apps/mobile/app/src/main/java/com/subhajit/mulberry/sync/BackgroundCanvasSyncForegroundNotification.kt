package com.subhajit.mulberry.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.subhajit.mulberry.MainActivity
import com.subhajit.mulberry.R

object BackgroundCanvasSyncForegroundNotification {
    private const val CHANNEL_ID = "canvas_sync"
    private const val CHANNEL_NAME = "Canvas sync"
    private const val NOTIFICATION_ID = 2201

    fun createForegroundInfo(context: Context): ForegroundInfo {
        ensureChannel(context)
        val notification = createNotification(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(context: Context) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.brand_iconmark_white)
        .setContentTitle("Syncing lock-screen canvas…")
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

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mulberry background canvas sync"
            }
        )
    }
}

