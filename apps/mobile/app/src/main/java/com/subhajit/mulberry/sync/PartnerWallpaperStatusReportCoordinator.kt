package com.subhajit.mulberry.sync

import android.util.Log
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.UpdateWallpaperStatusRequest
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

interface PartnerWallpaperStatusReportCoordinator {
    suspend fun reportNow(reason: String): Result<Unit>
}

@Singleton
class DefaultPartnerWallpaperStatusReportCoordinator @Inject constructor(
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository,
    private val wallpaperCoordinator: WallpaperCoordinator,
    private val apiService: MulberryApiService,
    private val scheduler: PartnerWallpaperStatusReportScheduler,
    @ApplicationScope applicationScope: CoroutineScope
) : PartnerWallpaperStatusReportCoordinator {

    init {
        applicationScope.launch {
            combine(
                wallpaperSyncSettingsRepository.enabled,
                sessionBootstrapRepository.state
            ) { wallpaperSyncEnabled, bootstrap ->
                ReportScheduleState(
                    wallpaperSyncEnabled = wallpaperSyncEnabled,
                    paired = bootstrap.pairingStatus == PairingStatus.PAIRED
                )
            }.distinctUntilChanged().collect { state ->
                if (!state.wallpaperSyncEnabled || !state.paired) {
                    scheduler.cancelPeriodic()
                } else {
                    scheduler.schedulePeriodic()
                }
            }
        }
    }

    override suspend fun reportNow(reason: String): Result<Unit> = runCatching {
        val session = sessionBootstrapRepository.getCurrentSession()
            ?: return@runCatching skip("signed out")
        val bootstrap = sessionBootstrapRepository.state.first()
        if (bootstrap.pairingStatus != PairingStatus.PAIRED || bootstrap.pairSessionId == null) {
            return@runCatching skip("not paired")
        }

        val wallpaperSyncEnabled = wallpaperSyncSettingsRepository.enabled.first()
        val status = wallpaperCoordinator.wallpaperStatus().first()
        apiService.updateWallpaperStatus(
            UpdateWallpaperStatusRequest(
                wallpaperSyncEnabled = wallpaperSyncEnabled,
                wallpaperSelectedOnHome = status.isWallpaperSelectedOnHome,
                wallpaperSelectedOnLock = status.isWallpaperSelectedOnLock
            )
        )
        Log.i(
            TAG,
            "Reported wallpaper status reason=$reason " +
                "pairSessionId=${bootstrap.pairSessionId} " +
                "syncEnabled=$wallpaperSyncEnabled " +
                "home=${status.isWallpaperSelectedOnHome} lock=${status.isWallpaperSelectedOnLock}"
        )
        Unit
    }

    private fun skip(reason: String) {
        Log.i(TAG, "Skipping wallpaper status report reason=$reason")
    }

    private data class ReportScheduleState(
        val wallpaperSyncEnabled: Boolean,
        val paired: Boolean
    )

    private companion object {
        const val TAG = "MulberryWallpaperStatus"
    }
}

