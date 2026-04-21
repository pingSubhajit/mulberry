package com.subhajit.mulberry.drawing

import com.subhajit.mulberry.drawing.model.CanvasState
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

    suspend fun replaceWithRemoteSnapshot(strokes: List<Stroke>, serverRevision: Long)

    suspend fun persistLocalCommittedStroke(stroke: Stroke): Long

    suspend fun persistRemoteCommittedStroke(stroke: Stroke, serverRevision: Long)

    suspend fun resetAllDrawingState()
}
