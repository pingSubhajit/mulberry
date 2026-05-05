package com.subhajit.mulberry.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import retrofit2.HttpException

@HiltWorker
class PartnerWallpaperStatusReportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: PartnerWallpaperStatusReportCoordinator
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting partner wallpaper status report")
        return coordinator.reportNow(reason = "workmanager")
            .fold(
                onSuccess = {
                    Result.success()
                },
                onFailure = { error ->
                    Log.w(TAG, "Partner wallpaper status report failed", error)
                    if (error is IOException || error is HttpException && error.code() >= 500) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
    }

    private companion object {
        const val TAG = "MulberryWallpaperStatus"
    }
}

