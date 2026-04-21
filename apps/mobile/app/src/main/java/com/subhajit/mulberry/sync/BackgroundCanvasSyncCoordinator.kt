package com.subhajit.mulberry.sync

import android.util.Log
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

sealed interface BackgroundCanvasSyncResult {
    data object Synced : BackgroundCanvasSyncResult
    data object AlreadyCurrent : BackgroundCanvasSyncResult
    data object Skipped : BackgroundCanvasSyncResult
}

interface BackgroundCanvasSyncCoordinator {
    suspend fun syncToLatestSnapshot(
        pairSessionId: String?,
        latestRevisionHint: Long?
    ): Result<BackgroundCanvasSyncResult>
}

@Singleton
class DefaultBackgroundCanvasSyncCoordinator @Inject constructor(
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val syncMetadataRepository: SyncMetadataRepository,
    private val apiService: MulberryApiService,
    private val drawingRepository: DrawingRepository,
    private val wallpaperCoordinator: WallpaperCoordinator
) : BackgroundCanvasSyncCoordinator {
    override suspend fun syncToLatestSnapshot(
        pairSessionId: String?,
        latestRevisionHint: Long?
    ): Result<BackgroundCanvasSyncResult> = runCatching {
        val session = sessionBootstrapRepository.getCurrentSession()
            ?: return@runCatching skip("signed out")
        val bootstrap = sessionBootstrapRepository.state.first()
        if (bootstrap.pairingStatus != PairingStatus.PAIRED || bootstrap.pairSessionId == null) {
            return@runCatching skip("not paired")
        }
        if (pairSessionId != null && pairSessionId != bootstrap.pairSessionId) {
            return@runCatching skip("pair session mismatch")
        }

        val localRevision = syncMetadataRepository.metadata.first().lastAppliedServerRevision
        Log.i(
            TAG,
            "Background snapshot sync check localRevision=$localRevision " +
                "latestRevisionHint=$latestRevisionHint pairSessionId=${bootstrap.pairSessionId}"
        )
        if (latestRevisionHint != null && latestRevisionHint < localRevision) {
            return@runCatching BackgroundCanvasSyncResult.AlreadyCurrent
        }

        val snapshot = apiService.getCanvasSnapshot()
        if (snapshot.pairSessionId != bootstrap.pairSessionId) {
            return@runCatching skip("snapshot pair session mismatch")
        }
        if (snapshot.revision < localRevision) {
            return@runCatching BackgroundCanvasSyncResult.AlreadyCurrent
        }

        drawingRepository.replaceWithRemoteSnapshot(
            strokes = snapshot.snapshot.strokes.map { stroke ->
                Stroke(
                    id = stroke.id,
                    colorArgb = stroke.colorArgb,
                    width = stroke.width,
                    createdAt = stroke.createdAt,
                    points = stroke.points.map { point ->
                        StrokePoint(x = point.x, y = point.y)
                    }
                )
            },
            serverRevision = snapshot.revision
        )
        syncMetadataRepository.setLastAppliedServerRevision(snapshot.revision)
        wallpaperCoordinator.ensureSnapshotCurrent()
        wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        Log.i(
            TAG,
            "Background snapshot sync applied revision=${snapshot.revision} " +
                "strokeCount=${snapshot.snapshot.strokes.size}"
        )
        BackgroundCanvasSyncResult.Synced
    }

    private fun skip(reason: String): BackgroundCanvasSyncResult {
        Log.i(TAG, "Skipping background snapshot sync reason=$reason")
        return BackgroundCanvasSyncResult.Skipped
    }

    private companion object {
        const val TAG = "MulberryBgSync"
    }
}
