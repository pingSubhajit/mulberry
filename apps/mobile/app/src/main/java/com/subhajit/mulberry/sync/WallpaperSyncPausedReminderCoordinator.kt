package com.subhajit.mulberry.sync

import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Singleton
class WallpaperSyncPausedReminderCoordinator @Inject constructor(
    sessionBootstrapRepository: SessionBootstrapRepository,
    wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository,
    private val reminderScheduler: WallpaperSyncPausedReminderScheduler,
    @ApplicationScope applicationScope: CoroutineScope
) {
    init {
        applicationScope.launch {
            combine(
                wallpaperSyncSettingsRepository.enabled,
                sessionBootstrapRepository.state
            ) { wallpaperSyncEnabled, bootstrap ->
                ReminderState(
                    wallpaperSyncEnabled = wallpaperSyncEnabled,
                    paired = bootstrap.pairingStatus == PairingStatus.PAIRED
                )
            }.distinctUntilChanged().collect { state ->
                if (state.wallpaperSyncEnabled || !state.paired) {
                    reminderScheduler.cancel()
                } else {
                    reminderScheduler.schedule()
                }
            }
        }
    }
}

private data class ReminderState(
    val wallpaperSyncEnabled: Boolean,
    val paired: Boolean
)

