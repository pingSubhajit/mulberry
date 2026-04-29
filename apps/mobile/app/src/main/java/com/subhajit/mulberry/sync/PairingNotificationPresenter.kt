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

object PairingNotificationPresenter {
    private const val NOTIFICATION_ID = 2101

    fun showPartnerJoined(context: Context, payload: PairingConfirmedPushPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PackageManager.PERMISSION_GRANTED) return
        }

        MulberryNotificationChannels.registerAll(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = AppShortcutAction.ShowPairingConfirmation.intentAction
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "${payload.actorDisplayName} joined you on Mulberry"
        val notification = NotificationCompat.Builder(
            context,
            MulberryNotificationChannels.CHANNEL_ID_PAIRING_UPDATES
        )
            .setSmallIcon(R.drawable.brand_iconmark_white)
            .setContentTitle(title)
            .setContentText("Open Mulberry to start sharing your lock-screen canvas.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Open Mulberry to start sharing your lock-screen canvas.")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun showPartnerUnpaired(context: Context, payload: PairingDisconnectedPushPayload) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PackageManager.PERMISSION_GRANTED) return
        }

        MulberryNotificationChannels.registerAll(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = AppShortcutAction.ShowPairingHub.intentAction
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "You're no longer paired with ${payload.actorDisplayName}"
        val message = "Open Mulberry to pair again."
        val notification = NotificationCompat.Builder(
            context,
            MulberryNotificationChannels.CHANNEL_ID_PAIRING_UPDATES
        )
            .setSmallIcon(R.drawable.brand_iconmark_white)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
