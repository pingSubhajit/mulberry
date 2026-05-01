package com.subhajit.mulberry.sync

import android.util.Log
import com.subhajit.mulberry.data.bootstrap.CanvasMode
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.geometry.containsLegacyGeometry
import com.subhajit.mulberry.drawing.geometry.hasLegacyGeometry
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.CanvasOperationBatchRequest
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.sync.CanvasKeys
import java.time.Instant
import java.util.UUID
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
    private val syncOutboxStore: CanvasSyncOutboxStore,
    private val apiService: MulberryApiService,
    private val drawingRepository: DrawingRepository,
    private val remoteOperationApplier: RemoteOperationApplier,
    private val wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository,
    private val wallpaperCoordinator: WallpaperCoordinator
) : BackgroundCanvasSyncCoordinator {
    override suspend fun syncToLatestSnapshot(
        pairSessionId: String?,
        latestRevisionHint: Long?
    ): Result<BackgroundCanvasSyncResult> = runCatching {
        if (!wallpaperSyncSettingsRepository.enabled.first()) {
            return@runCatching skip("wallpaper sync disabled")
        }
        val session = sessionBootstrapRepository.getCurrentSession()
            ?: return@runCatching skip("signed out")
        val bootstrap = sessionBootstrapRepository.state.first()
        if (bootstrap.pairingStatus != PairingStatus.PAIRED || bootstrap.pairSessionId == null) {
            return@runCatching skip("not paired")
        }
        if (pairSessionId != null && pairSessionId != bootstrap.pairSessionId) {
            return@runCatching skip("pair session mismatch")
        }
        preparePairSessionScope(bootstrap.pairSessionId)
        val resetStaleCount = syncOutboxStore.resetStaleInFlightToPending(
            staleBefore = System.currentTimeMillis() - BACKGROUND_IN_FLIGHT_STALE_MS
        )
        if (resetStaleCount > 0) {
            Log.i(
                TAG,
                "Reset stale background in-flight outbox operations count=$resetStaleCount"
            )
        }
        val outboxSummary = syncOutboxStore.summary()
        if (outboxSummary.pending > 0) {
            Log.i(
                TAG,
                "Flushing local outbox before background snapshot " +
                    "pending=${outboxSummary.pending} inFlight=${outboxSummary.inFlight}"
            )
            flushPendingOutbox()
        }
        val remainingOutbox = syncOutboxStore.summary()
        if (remainingOutbox.total > 0) {
            return@runCatching skip(
                "local outbox has pending work " +
                    "pending=${remainingOutbox.pending} inFlight=${remainingOutbox.inFlight}"
            )
        }

        val localRevision = syncMetadataRepository.metadata.first().lastAppliedServerRevision
        Log.i(
            TAG,
            "Background snapshot sync check localRevision=$localRevision " +
                "latestRevisionHint=$latestRevisionHint pairSessionId=${bootstrap.pairSessionId}"
        )

        val canvasKeys = if (
            bootstrap.canvasMode == CanvasMode.DEDICATED &&
                !bootstrap.partnerUserId.isNullOrBlank()
        ) {
            setOf(
                CanvasKeys.user(session.userId),
                CanvasKeys.user(bootstrap.partnerUserId!!)
            )
        } else {
            setOf(CanvasKeys.SHARED)
        }

        val snapshots = canvasKeys.associateWith { canvasKey ->
            apiService.getCanvasSnapshot(canvasKey = canvasKey)
        }
        if (snapshots.values.any { it.pairSessionId != bootstrap.pairSessionId }) {
            return@runCatching skip("snapshot pair session mismatch")
        }
        val latestRevision = snapshots.values.maxOfOrNull { it.latestRevision } ?: 0L
        if (latestRevision < localRevision) {
            Log.i(
                TAG,
                "Background snapshot is behind local state; refreshing wallpaper from local DB " +
                    "snapshotLatestRevision=$latestRevision localRevision=$localRevision"
            )
            wallpaperCoordinator.ensureSnapshotCurrent()
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
            return@runCatching BackgroundCanvasSyncResult.AlreadyCurrent
        }

        val snapshotRevisionsByKey = mutableMapOf<String, Long>()
        val snapshotStrokesByKey = mutableMapOf<String, List<Stroke>>()
        snapshots.forEach { (canvasKey, snapshot) ->
            val strokes = snapshot.snapshot.toDomainStrokes()
            snapshotRevisionsByKey[canvasKey] = snapshot.snapshotRevision
            snapshotStrokesByKey[canvasKey] = strokes
            if (strokes.containsLegacyGeometry()) {
                clearLegacyCanvasAndQueueReset(
                    reason = "background_snapshot_legacy_geometry",
                    canvasKeys = canvasKeys
                )
                wallpaperCoordinator.ensureSnapshotCurrent()
                wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
                return@runCatching BackgroundCanvasSyncResult.Synced
            }
        }

        val baseRevision = snapshotRevisionsByKey.values.minOrNull() ?: 0L
        snapshotStrokesByKey.forEach { (canvasKey, strokes) ->
            drawingRepository.replaceWithRemoteSnapshot(
                canvasKey = canvasKey,
                strokes = strokes,
                serverRevision = snapshotRevisionsByKey[canvasKey] ?: baseRevision
            )
        }
        syncMetadataRepository.setLastAppliedServerRevision(baseRevision)

        if (latestRevision > baseRevision) {
            val tail = apiService.getCanvasOperations(baseRevision)
                .operations
                .map { it.toDomainOperation() }
                .filter { it.serverRevision > baseRevision }
                .filter { operation ->
                    operation.canvasKey in canvasKeys &&
                        operation.serverRevision > (snapshotRevisionsByKey[operation.canvasKey] ?: baseRevision)
                }
                .sortedBy { it.serverRevision }
            if (tail.any { operation -> operation.hasLegacyGeometry() }) {
                clearLegacyCanvasAndQueueReset(
                    reason = "background_tail_legacy_geometry",
                    canvasKeys = canvasKeys
                )
                wallpaperCoordinator.ensureSnapshotCurrent()
                wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
                return@runCatching BackgroundCanvasSyncResult.Synced
            }
            tail.forEach { remoteOperationApplier.apply(it) }
        }
        wallpaperCoordinator.ensureSnapshotCurrent()
        wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        Log.i(
            TAG,
            "Background snapshot sync applied baseRevision=$baseRevision latestRevision=$latestRevision " +
                "canvasKeys=${canvasKeys.size} totalStrokeCount=${snapshotStrokesByKey.values.sumOf { it.size }}"
        )
        BackgroundCanvasSyncResult.Synced
    }

    private fun skip(reason: String): BackgroundCanvasSyncResult {
        Log.i(TAG, "Skipping background snapshot sync reason=$reason")
        return BackgroundCanvasSyncResult.Skipped
    }

    private suspend fun preparePairSessionScope(pairSessionId: String) {
        val metadata = syncMetadataRepository.metadata.first()
        if (metadata.pairSessionId == pairSessionId) return

        Log.i(
            TAG,
            "Switching background canvas sync scope " +
                "from=${metadata.pairSessionId ?: "none"} to=$pairSessionId"
        )
        syncOutboxStore.clear()
        syncMetadataRepository.resetForPairSession(pairSessionId)
        drawingRepository.resetAllDrawingState()
    }

    private suspend fun clearLegacyCanvasAndQueueReset(reason: String, canvasKeys: Set<String>) {
        Log.w(TAG, "Clearing legacy pixel-space background canvas data reason=$reason")
        syncOutboxStore.clear()
        drawingRepository.resetAllDrawingState()
        canvasKeys.forEach { canvasKey ->
            syncOutboxStore.enqueue(
                newClientOperation(
                    type = DrawingOperationType.CLEAR_CANVAS,
                    canvasKey = canvasKey,
                    strokeId = null,
                    payload = SyncOperationPayload.ClearCanvas
                )
            )
        }
    }

    private suspend fun flushPendingOutbox() {
        repeat(MAX_BACKGROUND_OUTBOX_BATCHES) {
            val operations = syncOutboxStore.nextBatch(
                maxOperations = MAX_BACKGROUND_OUTBOX_OPERATIONS,
                maxPayloadBytes = MAX_BACKGROUND_OUTBOX_PAYLOAD_BYTES
            )
            if (operations.isEmpty()) return
            val response = apiService.postCanvasOperationBatch(
                CanvasOperationBatchRequest(
                    batchId = UUID.randomUUID().toString(),
                    operations = operations.map { it.toClientRequest() },
                    clientCreatedAt = Instant.now().toString()
                )
            )
            syncOutboxStore.acknowledge(response.operations.map { it.clientOperationId })
            Log.i(
                TAG,
                "Flushed background outbox batch operations=${response.operations.size} " +
                    "latestAcceptedRevision=${response.operations.maxOfOrNull { it.serverRevision }}"
            )
        }
    }

    private companion object {
        const val TAG = "MulberryBgSync"
        const val MAX_BACKGROUND_OUTBOX_BATCHES = 8
        const val MAX_BACKGROUND_OUTBOX_OPERATIONS = 32
        const val MAX_BACKGROUND_OUTBOX_PAYLOAD_BYTES = 64 * 1024
        const val BACKGROUND_IN_FLIGHT_STALE_MS = 8_000L
    }
}

private fun com.subhajit.mulberry.network.CanvasSnapshotPayload.toDomainStrokes(): List<Stroke> =
    strokes.map { stroke ->
        Stroke(
            id = stroke.id,
            colorArgb = stroke.colorArgb,
            width = stroke.width,
            createdAt = stroke.createdAt,
            points = stroke.points.map { point ->
                StrokePoint(x = point.x, y = point.y)
            }
        )
    }
