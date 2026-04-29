package com.subhajit.mulberry

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.subhajit.mulberry.notifications.MulberryNotificationChannels
import com.subhajit.mulberry.sync.WallpaperSyncPausedReminderCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MulberryApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var wallpaperSyncPausedReminderCoordinator: WallpaperSyncPausedReminderCoordinator

    override fun onCreate() {
        super.onCreate()
        MulberryNotificationChannels.registerAll(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
