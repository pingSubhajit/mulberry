package com.subhajit.mulberry.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.subhajit.mulberry.core.data.PreferenceStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

interface WallpaperSyncPausedReminderScheduler {
    suspend fun schedule()
    suspend fun cancel()
}

@Singleton
class WorkManagerWallpaperSyncPausedReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) : WallpaperSyncPausedReminderScheduler {
    override suspend fun schedule() {
        var shouldEnqueue = false
        dataStore.edit { preferences ->
            if (preferences[PreferenceStorage.wallpaperSyncPausedSinceMs] == null) {
                preferences[PreferenceStorage.wallpaperSyncPausedSinceMs] = System.currentTimeMillis()
                preferences[PreferenceStorage.wallpaperSyncPausedReminderCount] = 0
                shouldEnqueue = true
            }
        }
        if (!shouldEnqueue) return

        val (delay, unit) = WallpaperSyncPausedReminderPolicy.delayForAttempt(0)
        enqueue(delay = delay, unit = unit)
    }

    override suspend fun cancel() {
        WorkManager.getInstance(context)
            .cancelUniqueWork(WallpaperSyncPausedReminderPolicy.UNIQUE_WORK_NAME)
        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.wallpaperSyncPausedReminderCount)
            preferences.remove(PreferenceStorage.wallpaperSyncPausedSinceMs)
        }
    }

    private fun enqueue(delay: Long, unit: TimeUnit) {
        val request = OneTimeWorkRequestBuilder<WallpaperSyncPausedReminderWorker>()
            .setInitialDelay(delay, unit)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WallpaperSyncPausedReminderPolicy.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
