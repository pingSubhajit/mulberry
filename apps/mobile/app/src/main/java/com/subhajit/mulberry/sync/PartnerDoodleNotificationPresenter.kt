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
import com.subhajit.mulberry.EnableWallpaperSyncActivity
import com.subhajit.mulberry.MainActivity
import com.subhajit.mulberry.R
import com.subhajit.mulberry.app.shortcut.AppShortcutAction
import com.subhajit.mulberry.notifications.MulberryNotificationChannels

object PartnerDoodleNotificationPresenter {
    private const val NOTIFICATION_ID = 2401

    fun show(context: Context, payload: CanvasNudgePushPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PackageManager.PERMISSION_GRANTED) return
        }

        MulberryNotificationChannels.registerAll(context)

        val actorName = payload.actorDisplayName.takeIf { it.isNotBlank() } ?: "Your partner"
        val notification = NotificationCompat.Builder(
            context,
            MulberryNotificationChannels.CHANNEL_ID_PARTNER_DOODLES
        )
            .setSmallIcon(R.drawable.brand_iconmark_white)
            .setContentTitle("New doodle from $actorName")
            .setContentText("Tap to see it in Mulberry.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Tap to see it in Mulberry.")
            )
            .setContentIntent(createLaunchIntent(context))
            .addAction(
                R.drawable.brand_iconmark_white,
                "Turn on Wallpaper sync",
                createEnableWallpaperSyncIntent(context)
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
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

    private fun createEnableWallpaperSyncIntent(context: Context): PendingIntent {
        val intent = Intent(context, EnableWallpaperSyncActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
