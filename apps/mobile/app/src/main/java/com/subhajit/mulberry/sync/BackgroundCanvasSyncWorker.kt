package com.subhajit.mulberry.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

@HiltWorker
class BackgroundCanvasSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: BackgroundCanvasSyncCoordinator
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pairSessionId = inputData.getString(KEY_PAIR_SESSION_ID)
        val latestRevision = inputData.getLong(KEY_LATEST_REVISION, 0L).takeIf { it > 0L }
        Log.i(
            TAG,
            "Starting background canvas sync pairSessionId=$pairSessionId " +
                "latestRevision=$latestRevision"
        )
        return coordinator.syncToLatestSnapshot(pairSessionId, latestRevision)
            .fold(
                onSuccess = { result ->
                    Log.i(TAG, "Background canvas sync finished result=$result")
                    Result.success()
                },
                onFailure = { error ->
                    Log.w(TAG, "Background canvas sync failed", error)
                    if (error is IOException || error is HttpException && error.code() >= 500) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
    }

    companion object {
        private const val TAG = "MulberryBgSync"
        const val KEY_PAIR_SESSION_ID = "pairSessionId"
        const val KEY_LATEST_REVISION = "latestRevision"
    }
}
