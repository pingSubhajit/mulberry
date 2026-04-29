package com.subhajit.mulberry.sync

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.subhajit.mulberry.core.data.PreferenceStorage
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

@HiltWorker
class WallpaperSyncPausedReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val dataStore: DataStore<Preferences>
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (wallpaperSyncSettingsRepository.enabled.first()) {
            clearStateAndCancelWork()
            return Result.success()
        }

        val bootstrap = sessionBootstrapRepository.state.first()
        if (bootstrap.pairingStatus != PairingStatus.PAIRED) {
            clearStateAndCancelWork()
            return Result.success()
        }

        WallpaperSyncPausedReminderNotification.show(applicationContext)

        val nextAttempt = incrementAttemptAndEnsurePausedSince()
        val (delay, unit) = WallpaperSyncPausedReminderPolicy.delayForAttempt(nextAttempt)
        enqueueNext(delay = delay, unit = unit)
        Log.i(TAG, "Scheduled next wallpaper sync paused reminder attempt=$nextAttempt delay=$delay $unit")
        return Result.success()
    }

    private suspend fun incrementAttemptAndEnsurePausedSince(): Int {
        var updatedAttempt = 0
        dataStore.edit { preferences ->
            val currentAttempt = preferences[PreferenceStorage.wallpaperSyncPausedReminderCount] ?: 0
            updatedAttempt = currentAttempt + 1
            preferences[PreferenceStorage.wallpaperSyncPausedReminderCount] = updatedAttempt
            if (preferences[PreferenceStorage.wallpaperSyncPausedSinceMs] == null) {
                preferences[PreferenceStorage.wallpaperSyncPausedSinceMs] = System.currentTimeMillis()
            }
        }
        return updatedAttempt
    }

    private fun enqueueNext(delay: Long, unit: TimeUnit) {
        val request = OneTimeWorkRequestBuilder<WallpaperSyncPausedReminderWorker>()
            .setInitialDelay(delay, unit)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WallpaperSyncPausedReminderPolicy.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private suspend fun clearStateAndCancelWork() {
        WorkManager.getInstance(applicationContext)
            .cancelUniqueWork(WallpaperSyncPausedReminderPolicy.UNIQUE_WORK_NAME)
        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.wallpaperSyncPausedReminderCount)
            preferences.remove(PreferenceStorage.wallpaperSyncPausedSinceMs)
        }
    }

    private companion object {
        const val TAG = "MulberryWallpaperSync"
    }
}
