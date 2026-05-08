package com.subhajit.mulberry

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.subhajit.mulberry.app.shortcut.ReactionShortcutPublisher
import com.subhajit.mulberry.notifications.MulberryNotificationChannels
import com.subhajit.mulberry.reactions.ReactionLocalStore
import com.subhajit.mulberry.sync.PartnerWallpaperStatusReportCoordinator
import com.subhajit.mulberry.sync.WallpaperSyncPausedReminderCoordinator
import com.subhajit.mulberry.widget.relationship.RelationshipWidgetAnniversaryObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MulberryApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var wallpaperSyncPausedReminderCoordinator: WallpaperSyncPausedReminderCoordinator
    @Inject lateinit var partnerWallpaperStatusReportCoordinator: PartnerWallpaperStatusReportCoordinator
    @Inject lateinit var reactionLocalStore: ReactionLocalStore
    @Inject lateinit var relationshipWidgetAnniversaryObserver: RelationshipWidgetAnniversaryObserver

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        MulberryNotificationChannels.registerAll(this)
        relationshipWidgetAnniversaryObserver.start(applicationScope)
        applicationScope.launch {
            ReactionShortcutPublisher.publish(
                context = this@MulberryApplication,
                reactionType = reactionLocalStore.getLastUsedReaction()
            )
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
