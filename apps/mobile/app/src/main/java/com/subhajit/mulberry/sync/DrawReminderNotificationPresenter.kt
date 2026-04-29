package com.subhajit.mulberry.sync

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.subhajit.mulberry.MainActivity
import com.subhajit.mulberry.R
import com.subhajit.mulberry.app.shortcut.AppShortcutAction
import com.subhajit.mulberry.notifications.MulberryNotificationChannels

object DrawReminderNotificationPresenter {
    private const val NOTIFICATION_ID = 2402

    fun show(context: Context, payload: DrawReminderPushPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PackageManager.PERMISSION_GRANTED) return
        }

        MulberryNotificationChannels.registerAll(context)

        val partnerName = payload.partnerDisplayName.takeIf { it.isNotBlank() } ?: "Your partner"
        val notification = NotificationCompat.Builder(
            context,
            MulberryNotificationChannels.CHANNEL_ID_DRAW_REMINDERS
        )
            .setSmallIcon(R.drawable.brand_iconmark_white)
            .setContentTitle("Draw something for $partnerName")
            .setContentText("It’s been a while. Tap to open your canvas.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("It’s been a while. Tap to open your canvas.")
            )
            .setContentIntent(createLaunchIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createLaunchIntent(context: Context): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = AppShortcutAction.ChangeWallpaper.intentAction
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
