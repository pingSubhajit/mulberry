package com.subhajit.mulberry.sync

import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.Stroke
import javax.inject.Inject
import javax.inject.Singleton

interface RemoteOperationApplier {
    suspend fun apply(operation: ServerCanvasOperation)
}

@Singleton
class DefaultRemoteOperationApplier @Inject constructor(
    private val drawingRepository: DrawingRepository,
    private val syncMetadataRepository: SyncMetadataRepository
) : RemoteOperationApplier {
    override suspend fun apply(operation: ServerCanvasOperation) {
        when (operation.payload) {
            is SyncOperationPayload.AddStroke -> {
                val payload = operation.payload
                drawingRepository.applyRemoteAddStroke(
                    stroke = Stroke(
                        id = payload.id,
                        colorArgb = payload.colorArgb,
                        width = payload.width,
                        points = listOf(payload.firstPoint),
                        createdAt = payload.createdAt
                    ),
                    serverRevision = operation.serverRevision
                )
            }
            is SyncOperationPayload.AppendPoints -> {
                drawingRepository.applyRemoteAppendPoints(
                    strokeId = operation.strokeId ?: return,
                    points = operation.payload.points,
                    serverRevision = operation.serverRevision
                )
            }
            SyncOperationPayload.FinishStroke -> {
                drawingRepository.applyRemoteFinishStroke(
                    strokeId = operation.strokeId ?: return,
                    serverRevision = operation.serverRevision
                )
            }
            SyncOperationPayload.DeleteStroke -> {
                drawingRepository.applyRemoteDeleteStroke(
                    strokeId = operation.strokeId ?: return,
                    serverRevision = operation.serverRevision
                )
            }
            SyncOperationPayload.ClearCanvas -> {
                drawingRepository.applyRemoteClearCanvas(operation.serverRevision)
            }
        }
        syncMetadataRepository.setLastAppliedServerRevision(operation.serverRevision)
    }
}
