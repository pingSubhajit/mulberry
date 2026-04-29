package com.subhajit.mulberry.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.core.content.ContextCompat
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
            val requestBuilder = OneTimeWorkRequestBuilder<BackgroundCanvasSyncWorker>()
                .setInputData(
                    workDataOf(
                        BackgroundCanvasSyncWorker.KEY_PAIR_SESSION_ID to pairSessionId,
                        BackgroundCanvasSyncWorker.KEY_LATEST_REVISION to (latestRevision ?: 0L)
                    )
                )
            if (canUseExpeditedWork()) {
                requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val request = requestBuilder.build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    private fun canUseExpeditedWork(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val permission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        )
        return permission == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "MulberryBgSync"
    }
}
