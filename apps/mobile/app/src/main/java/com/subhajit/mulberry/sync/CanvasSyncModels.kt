package com.subhajit.mulberry.sync

import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint

sealed interface SyncState {
    data object Disconnected : SyncState
    data object Connecting : SyncState
    data object Connected : SyncState
    data object Recovering : SyncState
    data class Error(val message: String) : SyncState
}

data class CanvasSyncOperation(
    val clientOperationId: String,
    val type: DrawingOperationType,
    val strokeId: String?,
    val payload: SyncOperationPayload,
    val clientCreatedAt: String
)

data class ServerCanvasOperation(
    val clientOperationId: String,
    val actorUserId: String,
    val pairSessionId: String,
    val type: DrawingOperationType,
    val strokeId: String?,
    val payload: SyncOperationPayload,
    val clientCreatedAt: String,
    val serverRevision: Long,
    val createdAt: String
)

sealed interface SyncOperationPayload {
    data class AddStroke(
        val id: String,
        val colorArgb: Long,
        val width: Float,
        val createdAt: Long,
        val firstPoint: StrokePoint
    ) : SyncOperationPayload

    data class AppendPoints(
        val points: List<StrokePoint>
    ) : SyncOperationPayload

    data object FinishStroke : SyncOperationPayload

    data object DeleteStroke : SyncOperationPayload

    data object ClearCanvas : SyncOperationPayload

    data class AddTextElement(
        val id: String,
        val text: String,
        val createdAt: Long,
        val center: StrokePoint,
        val rotationRad: Float,
        val scale: Float,
        val boxWidth: Float,
        val colorArgb: Long,
        val backgroundPillEnabled: Boolean,
        val font: CanvasTextFont,
        val alignment: CanvasTextAlign
    ) : SyncOperationPayload

    data class UpdateTextElement(
        val id: String,
        val text: String,
        val createdAt: Long,
        val center: StrokePoint,
        val rotationRad: Float,
        val scale: Float,
        val boxWidth: Float,
        val colorArgb: Long,
        val backgroundPillEnabled: Boolean,
        val font: CanvasTextFont,
        val alignment: CanvasTextAlign
    ) : SyncOperationPayload

    data object DeleteTextElement : SyncOperationPayload

    data class AddStickerElement(
        val id: String,
        val createdAt: Long,
        val center: StrokePoint,
        val rotationRad: Float,
        val scale: Float,
        val packKey: String,
        val packVersion: Int,
        val stickerId: String
    ) : SyncOperationPayload

    data class UpdateStickerElement(
        val id: String,
        val createdAt: Long,
        val center: StrokePoint,
        val rotationRad: Float,
        val scale: Float,
        val packKey: String,
        val packVersion: Int,
        val stickerId: String
    ) : SyncOperationPayload

    data object DeleteStickerElement : SyncOperationPayload
}

fun Stroke.toAddStrokePayload(): SyncOperationPayload.AddStroke =
    SyncOperationPayload.AddStroke(
        id = id,
        colorArgb = colorArgb,
        width = width,
        createdAt = createdAt,
        firstPoint = points.first()
    )
