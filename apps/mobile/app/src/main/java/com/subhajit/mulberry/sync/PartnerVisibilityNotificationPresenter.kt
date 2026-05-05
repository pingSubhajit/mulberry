package com.subhajit.mulberry.sync

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.subhajit.mulberry.MainActivity
import com.subhajit.mulberry.R
import com.subhajit.mulberry.app.shortcut.AppShortcutAction
import com.subhajit.mulberry.notifications.MulberryNotificationChannels
import android.app.NotificationManager

object PartnerVisibilityNotificationPresenter {
    private const val NOTIFICATION_ID = 2501

    fun show(context: Context, payload: PartnerVisibilityChangedPushPayload) {
        Log.i(
            TAG,
            "Preparing partner visibility notification canSeeLatest=${payload.canSeeLatestDrawings} " +
                "syncEnabled=${payload.wallpaperSyncEnabled} home=${payload.wallpaperSelectedOnHome} lock=${payload.wallpaperSelectedOnLock}"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permission != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Skipping partner visibility notification (POST_NOTIFICATIONS not granted)")
                return
            }
        }

        val notificationManagerCompat = NotificationManagerCompat.from(context)
        if (!notificationManagerCompat.areNotificationsEnabled()) {
            Log.i(TAG, "Skipping partner visibility notification (notifications disabled for app)")
            return
        }

        MulberryNotificationChannels.registerAll(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val systemManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = systemManager?.getNotificationChannel(MulberryNotificationChannels.CHANNEL_ID_PARTNER_VISIBILITY)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.i(TAG, "Skipping partner visibility notification (channel disabled by user)")
                return
            }
        }

        val actorName = payload.actorDisplayName.takeIf { it.isNotBlank() } ?: "Your partner"

        val (title, message, action) = if (payload.canSeeLatestDrawings) {
            Triple(
                "$actorName can see your latest drawings",
                "Tap to open Mulberry.",
                AppShortcutAction.OpenCanvas
            )
        } else {
            val reason = if (!payload.wallpaperSyncEnabled) {
                "“Show doodles on wallpaper” is off on their device."
            } else {
                "Mulberry isn’t set as wallpaper on their lock or home screen."
            }
            Triple(
                "$actorName can’t see your latest drawings",
                "$reason Tap to learn how to fix it.",
                AppShortcutAction.ShowPartnerVisibilitySheet
            )
        }

        val notification = NotificationCompat.Builder(
            context,
            MulberryNotificationChannels.CHANNEL_ID_PARTNER_VISIBILITY
        )
            .setSmallIcon(R.drawable.brand_iconmark_white)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(createLaunchIntent(context, action))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            notificationManagerCompat.notify(NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.w(TAG, "Failed to post partner visibility notification", error)
            return
        }
        Log.i(TAG, "Posted partner visibility notification id=$NOTIFICATION_ID")
    }

    private fun createLaunchIntent(context: Context, action: AppShortcutAction): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            this.action = action.intentAction
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val TAG = "MulberryPartnerVisibility"
}
