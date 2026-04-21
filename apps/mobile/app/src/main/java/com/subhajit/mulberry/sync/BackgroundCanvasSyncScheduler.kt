package com.subhajit.mulberry.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface BackgroundCanvasSyncScheduler {
    fun enqueueCanvasUpdated(pairSessionId: String?, latestRevision: Long?)
}

@Singleton
class WorkManagerBackgroundCanvasSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : BackgroundCanvasSyncScheduler {
    override fun enqueueCanvasUpdated(pairSessionId: String?, latestRevision: Long?) {
        val workName = "canvas-background-sync-${pairSessionId ?: "default"}"
        Log.i(
            TAG,
            "Enqueue background canvas sync workName=$workName latestRevision=$latestRevision"
        )
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

    private companion object {
        const val TAG = "MulberryBgSync"
    }
}
