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
import com.subhajit.mulberry.notifications.MulberryNotificationChannels

object CanvasModeChangedNotificationPresenter {
    private const val NOTIFICATION_ID = 2404

    fun show(context: Context, payload: CanvasModeChangedPushPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PackageManager.PERMISSION_GRANTED) return
        }

        MulberryNotificationChannels.registerAll(context)

        val actorName = payload.actorDisplayName.takeIf { it.isNotBlank() } ?: "Your partner"
        val mode = payload.canvasMode.displayName
        val notification = NotificationCompat.Builder(
            context,
            MulberryNotificationChannels.CHANNEL_ID_PAIRING_UPDATES
        )
            .setSmallIcon(R.drawable.brand_iconmark_white)
            .setContentTitle("$actorName switched canvas mode")
            .setContentText("Now using $mode canvas.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Now using $mode canvas. Tap to open Mulberry.")
            )
            .setContentIntent(createLaunchIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    // TODO: Add a notification preference for canvas mode change alerts.
    private fun createLaunchIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

