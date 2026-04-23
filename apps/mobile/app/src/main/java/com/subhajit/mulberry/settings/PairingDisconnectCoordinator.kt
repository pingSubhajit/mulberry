package com.subhajit.mulberry.settings

import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.pairing.InviteRepository
import com.subhajit.mulberry.sync.CanvasSyncRepository
import com.subhajit.mulberry.wallpaper.CanvasSnapshotRenderer
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PairingDisconnectCoordinator @Inject constructor(
    private val pairingSettingsRepository: PairingSettingsRepository,
    private val inviteRepository: InviteRepository,
    private val drawingRepository: DrawingRepository,
    private val canvasSyncRepository: CanvasSyncRepository,
    private val canvasSnapshotRenderer: CanvasSnapshotRenderer,
    private val wallpaperCoordinator: WallpaperCoordinator
) {
    suspend fun disconnectPartner(): Result<Unit> = runCatching {
        pairingSettingsRepository.disconnectPartner().getOrThrow()
        inviteRepository.clearCurrentInvite()
        canvasSyncRepository.reset()
        drawingRepository.resetAllDrawingState()
        canvasSnapshotRenderer.clearSnapshots()
        wallpaperCoordinator.ensureSnapshotCurrent()
        wallpaperCoordinator.notifyWallpaperUpdated()
    }
}
