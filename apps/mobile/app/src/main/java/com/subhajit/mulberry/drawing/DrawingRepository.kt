package com.subhajit.mulberry.drawing

import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.ToolState
import kotlinx.coroutines.flow.Flow

interface DrawingRepository {
    val canvasState: Flow<CanvasState>
    val toolState: Flow<ToolState>

    suspend fun startStroke(point: StrokePoint): Stroke?

    suspend fun appendPoint(point: StrokePoint): Stroke?

    suspend fun finishStroke(): Stroke?

    suspend fun setBrushColor(colorArgb: Long)

    suspend fun setTextColor(colorArgb: Long)

    suspend fun setBrushWidth(width: Float)

    suspend fun setTool(tool: DrawingTool)

    suspend fun setCanvasViewport(widthPx: Int, heightPx: Int)

    suspend fun eraseStroke(strokeId: String)

    suspend fun clearCanvas()

    suspend fun applyRemoteAddStroke(stroke: Stroke, serverRevision: Long)

    suspend fun applyRemoteAppendPoints(strokeId: String, points: List<StrokePoint>, serverRevision: Long)

    suspend fun applyRemoteFinishStroke(strokeId: String, serverRevision: Long)

    suspend fun applyRemoteDeleteStroke(strokeId: String, serverRevision: Long)

    suspend fun applyRemoteClearCanvas(serverRevision: Long)

    suspend fun upsertLocalTextElement(element: CanvasTextElement): Long

    suspend fun deleteLocalTextElement(elementId: String): Long

    suspend fun applyRemoteAddOrUpdateTextElement(element: CanvasTextElement, serverRevision: Long)

    suspend fun applyRemoteDeleteTextElement(elementId: String, serverRevision: Long)

    suspend fun upsertLocalStickerElement(element: CanvasStickerElement): Long

    suspend fun deleteLocalStickerElement(elementId: String): Long

    suspend fun applyRemoteAddOrUpdateStickerElement(element: CanvasStickerElement, serverRevision: Long)

    suspend fun applyRemoteDeleteStickerElement(elementId: String, serverRevision: Long)

    suspend fun replaceWithRemoteSnapshot(
        strokes: List<Stroke>,
        elements: List<CanvasElement>,
        serverRevision: Long
    )

    suspend fun persistLocalCommittedStroke(stroke: Stroke): Long

    suspend fun persistRemoteCommittedStroke(stroke: Stroke, serverRevision: Long)

    suspend fun resetAllDrawingState()
}
