package com.subhajit.mulberry.sync

import android.content.Context
import android.util.Log
import com.subhajit.mulberry.app.AppForegroundState
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class CanvasNudgeNotificationHandler @Inject constructor(
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository,
    private val wallpaperCoordinator: WallpaperCoordinator
) {
    suspend fun shouldNotify(payload: CanvasNudgePushPayload): Boolean {
        if (AppForegroundState.isForeground.value) return false

        val bootstrap = sessionBootstrapRepository.state.first()
        if (bootstrap.pairingStatus != PairingStatus.PAIRED) return false
        val activePairSessionId = bootstrap.pairSessionId ?: return false
        if (payload.pairSessionId == null || payload.pairSessionId != activePairSessionId) return false

        val latestRevision = payload.latestRevision ?: return false
        val metadata = syncMetadataRepository.metadata.first()
        if (latestRevision <= metadata.lastAppliedServerRevision) return false

        val wallpaperSyncEnabled = wallpaperSyncSettingsRepository.enabled.first()
        val wallpaperSelected = wallpaperCoordinator.wallpaperStatus().first().isWallpaperSelected
        if (wallpaperSyncEnabled && wallpaperSelected) return false

        return true
    }

    suspend fun handleNudge(context: Context, payload: CanvasNudgePushPayload): Boolean {
        val should = runCatching { shouldNotify(payload) }.getOrDefault(false)
        if (!should) return false
        PartnerDoodleNotificationPresenter.show(context, payload)
        return true
    }

    suspend fun debugEvaluateAndLog(payload: CanvasNudgePushPayload): Boolean {
        val should = shouldNotify(payload)
        Log.i(TAG, "Canvas nudge gating shouldNotify=$should payloadLatest=${payload.latestRevision}")
        return should
    }

    private companion object {
        const val TAG = "MulberryNudge"
    }
}

