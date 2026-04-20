package com.subhajit.elaris.drawing

import com.subhajit.elaris.drawing.model.CanvasState
import com.subhajit.elaris.drawing.model.DrawingTool
import com.subhajit.elaris.drawing.model.StrokePoint
import com.subhajit.elaris.drawing.model.ToolState
import kotlinx.coroutines.flow.Flow

interface DrawingRepository {
    val canvasState: Flow<CanvasState>
    val toolState: Flow<ToolState>

    suspend fun startStroke(point: StrokePoint)

    suspend fun appendPoint(point: StrokePoint)

    suspend fun finishStroke()

    suspend fun setBrushColor(colorArgb: Long)

    suspend fun setBrushWidth(width: Float)

    suspend fun setTool(tool: DrawingTool)

    suspend fun eraseStroke(strokeId: String)

    suspend fun clearCanvas()

    suspend fun resetAllDrawingState()
}
