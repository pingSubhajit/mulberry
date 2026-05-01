package com.subhajit.mulberry.sync

import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
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
            is SyncOperationPayload.AddTextElement -> {
                drawingRepository.applyRemoteAddOrUpdateTextElement(
                    element = operation.payload.toDomainElement(),
                    serverRevision = operation.serverRevision
                )
            }
            is SyncOperationPayload.UpdateTextElement -> {
                drawingRepository.applyRemoteAddOrUpdateTextElement(
                    element = operation.payload.toDomainElement(),
                    serverRevision = operation.serverRevision
                )
            }
            SyncOperationPayload.DeleteTextElement -> {
                drawingRepository.applyRemoteDeleteTextElement(
                    elementId = operation.strokeId ?: return,
                    serverRevision = operation.serverRevision
                )
            }
        }
        syncMetadataRepository.setLastAppliedServerRevision(operation.serverRevision)
    }
}

private fun SyncOperationPayload.AddTextElement.toDomainElement(): CanvasTextElement = CanvasTextElement(
    id = id,
    text = text,
    createdAt = createdAt,
    center = StrokePoint(x = center.x, y = center.y),
    rotationRad = rotationRad,
    scale = scale,
    boxWidth = boxWidth,
    colorArgb = colorArgb,
    backgroundPillEnabled = backgroundPillEnabled,
    font = font,
    alignment = alignment
)

private fun SyncOperationPayload.UpdateTextElement.toDomainElement(): CanvasTextElement = CanvasTextElement(
    id = id,
    text = text,
    createdAt = createdAt,
    center = StrokePoint(x = center.x, y = center.y),
    rotationRad = rotationRad,
    scale = scale,
    boxWidth = boxWidth,
    colorArgb = colorArgb,
    backgroundPillEnabled = backgroundPillEnabled,
    font = font,
    alignment = alignment
)
