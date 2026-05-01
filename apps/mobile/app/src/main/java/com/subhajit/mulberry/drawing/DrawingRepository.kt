package com.subhajit.mulberry.drawing

import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.ToolState
import kotlinx.coroutines.flow.Flow

interface DrawingRepository {
    fun canvasState(canvasKey: String): Flow<CanvasState>
    fun toolState(canvasKey: String): Flow<ToolState>

    suspend fun startStroke(canvasKey: String, point: StrokePoint): Stroke?

    suspend fun appendPoint(canvasKey: String, point: StrokePoint): Stroke?

    suspend fun finishStroke(canvasKey: String): Stroke?

    suspend fun setBrushColor(canvasKey: String, colorArgb: Long)

    suspend fun setBrushWidth(canvasKey: String, width: Float)

    suspend fun setTool(canvasKey: String, tool: DrawingTool)

    suspend fun setCanvasViewport(canvasKey: String, widthPx: Int, heightPx: Int)

    suspend fun eraseStroke(canvasKey: String, strokeId: String)

    suspend fun clearCanvas(canvasKey: String)

    suspend fun applyRemoteAddStroke(canvasKey: String, stroke: Stroke, serverRevision: Long)

    suspend fun applyRemoteAppendPoints(
        canvasKey: String,
        strokeId: String,
        points: List<StrokePoint>,
        serverRevision: Long
    )

    suspend fun applyRemoteFinishStroke(canvasKey: String, strokeId: String, serverRevision: Long)

    suspend fun applyRemoteDeleteStroke(canvasKey: String, strokeId: String, serverRevision: Long)

    suspend fun applyRemoteClearCanvas(canvasKey: String, serverRevision: Long)

    suspend fun replaceWithRemoteSnapshot(canvasKey: String, strokes: List<Stroke>, serverRevision: Long)

    suspend fun persistLocalCommittedStroke(canvasKey: String, stroke: Stroke): Long

    suspend fun persistRemoteCommittedStroke(canvasKey: String, stroke: Stroke, serverRevision: Long)

    suspend fun resetAllDrawingState()
}
