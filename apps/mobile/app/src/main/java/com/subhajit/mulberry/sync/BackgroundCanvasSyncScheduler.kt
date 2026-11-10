package com.subhajit.mulberry.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

interface BackgroundCanvasSyncScheduler {
    fun enqueueCanvasUpdated(pairSessionId: String?, latestRevision: Long?)
}

@Singleton
class WorkManagerBackgroundCanvasSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository,
    @ApplicationScope private val applicationScope: CoroutineScope
) : BackgroundCanvasSyncScheduler {
    override fun enqueueCanvasUpdated(pairSessionId: String?, latestRevision: Long?) {
        applicationScope.launch {
            if (!wallpaperSyncSettingsRepository.enabled.first()) {
                Log.i(TAG, "Skipping background canvas sync enqueue; wallpaper sync disabled")
                return@launch
            }

            val workName = "canvas-background-sync-${pairSessionId ?: "default"}"
            Log.i(
                TAG,
                "Enqueue background canvas sync workName=$workName latestRevision=$latestRevision"
            )
            // Expedited work needs no notification permission: Android 12 and newer run it as an
            // expedited job, and older versions have no runtime notification permission.
            val request = OneTimeWorkRequestBuilder<BackgroundCanvasSyncWorker>()
                .setInputData(
                    workDataOf(
                        BackgroundCanvasSyncWorker.KEY_PAIR_SESSION_ID to pairSessionId,
                        BackgroundCanvasSyncWorker.KEY_LATEST_REVISION to (latestRevision ?: 0L)
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    private companion object {
        const val TAG = "MulberryBgSync"
    }
}
