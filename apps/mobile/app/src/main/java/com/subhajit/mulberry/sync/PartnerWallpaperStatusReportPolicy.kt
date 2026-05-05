package com.subhajit.mulberry.sync

import androidx.work.Constraints
import androidx.work.NetworkType

object PartnerWallpaperStatusReportPolicy {
    const val UNIQUE_PERIODIC_WORK_NAME = "partner_wallpaper_status_report_periodic"
    const val UNIQUE_IMMEDIATE_WORK_NAME = "partner_wallpaper_status_report_immediate"

    const val REPEAT_INTERVAL_HOURS = 6L

    val constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}

