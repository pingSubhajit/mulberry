package com.subhajit.mulberry.sync

import android.content.Context
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
        return coordinator.syncToLatestSnapshot(pairSessionId, latestRevision)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    if (error is IOException || error is HttpException && error.code() >= 500) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            )
    }

    companion object {
        const val KEY_PAIR_SESSION_ID = "pairSessionId"
        const val KEY_LATEST_REVISION = "latestRevision"
    }
}
