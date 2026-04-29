package com.subhajit.mulberry.sync

import java.util.concurrent.TimeUnit

object WallpaperSyncPausedReminderPolicy {
    const val UNIQUE_WORK_NAME = "wallpaper-sync-paused-reminder"

    fun delayForAttempt(attempt: Int): Pair<Long, TimeUnit> = when {
        attempt <= 0 -> 1L to TimeUnit.HOURS
        attempt == 1 -> 6L to TimeUnit.HOURS
        attempt == 2 -> 24L to TimeUnit.HOURS
        else -> 72L to TimeUnit.HOURS
    }
}

