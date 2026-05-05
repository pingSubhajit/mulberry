package com.subhajit.mulberry.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface PartnerWallpaperStatusReportScheduler {
    fun schedulePeriodic()
    fun cancelPeriodic()
    fun enqueueImmediate()
}

@Singleton
class WorkManagerPartnerWallpaperStatusReportScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : PartnerWallpaperStatusReportScheduler {
    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<PartnerWallpaperStatusReportWorker>(
            PartnerWallpaperStatusReportPolicy.REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(PartnerWallpaperStatusReportPolicy.constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PartnerWallpaperStatusReportPolicy.UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    override fun cancelPeriodic() {
        WorkManager.getInstance(context)
            .cancelUniqueWork(PartnerWallpaperStatusReportPolicy.UNIQUE_PERIODIC_WORK_NAME)
    }

    override fun enqueueImmediate() {
        val request = OneTimeWorkRequestBuilder<PartnerWallpaperStatusReportWorker>()
            .setConstraints(PartnerWallpaperStatusReportPolicy.constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PartnerWallpaperStatusReportPolicy.UNIQUE_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

