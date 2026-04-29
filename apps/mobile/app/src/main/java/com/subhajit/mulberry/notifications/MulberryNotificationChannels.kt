package com.subhajit.mulberry.notifications

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.subhajit.mulberry.R

object MulberryNotificationChannels {
    const val GROUP_ID_PARTNER = "partner"
    const val GROUP_ID_REMINDERS = "reminders"
    const val GROUP_ID_SYNC = "sync"

    const val CHANNEL_ID_PAIRING_UPDATES = "pairing_updates"
    const val CHANNEL_ID_PARTNER_DOODLES = "partner_doodles"
    const val CHANNEL_ID_DRAW_REMINDERS = "draw_reminders"
    const val CHANNEL_ID_WALLPAPER_SYNC = "wallpaper_sync"
    const val CHANNEL_ID_CANVAS_SYNC = "canvas_sync"

    fun registerAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val groups = listOf(
            NotificationChannelGroup(
                GROUP_ID_PARTNER,
                context.getString(R.string.notification_group_partner)
            ),
            NotificationChannelGroup(
                GROUP_ID_REMINDERS,
                context.getString(R.string.notification_group_reminders)
            ),
            NotificationChannelGroup(
                GROUP_ID_SYNC,
                context.getString(R.string.notification_group_sync)
            )
        )
        groups.forEach(notificationManager::createNotificationChannelGroup)

        val channels = listOf(
            NotificationChannel(
                CHANNEL_ID_PAIRING_UPDATES,
                context.getString(R.string.notification_channel_pairing_updates_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_pairing_updates_description)
                group = GROUP_ID_PARTNER
            },
            NotificationChannel(
                CHANNEL_ID_PARTNER_DOODLES,
                context.getString(R.string.notification_channel_partner_doodles_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_partner_doodles_description)
                group = GROUP_ID_PARTNER
            },
            NotificationChannel(
                CHANNEL_ID_DRAW_REMINDERS,
                context.getString(R.string.notification_channel_draw_reminders_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notification_channel_draw_reminders_description)
                group = GROUP_ID_REMINDERS
            },
            NotificationChannel(
                CHANNEL_ID_WALLPAPER_SYNC,
                context.getString(R.string.notification_channel_wallpaper_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_wallpaper_sync_description)
                group = GROUP_ID_REMINDERS
            },
            NotificationChannel(
                CHANNEL_ID_CANVAS_SYNC,
                context.getString(R.string.notification_channel_canvas_sync_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_canvas_sync_description)
                group = GROUP_ID_SYNC
            }
        )
        notificationManager.createNotificationChannels(channels)
    }
}

