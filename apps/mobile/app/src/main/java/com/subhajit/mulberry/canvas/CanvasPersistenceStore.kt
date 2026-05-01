package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.ToolState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface CanvasPersistenceStore {
    suspend fun loadCanvasState(canvasKey: String): CanvasState
    suspend fun loadToolState(canvasKey: String): ToolState
    suspend fun persistLocalCommittedStroke(canvasKey: String, stroke: Stroke): Long
    suspend fun persistRemoteCommittedStroke(canvasKey: String, stroke: Stroke, serverRevision: Long)
    suspend fun persistErase(canvasKey: String, strokeId: String, serverRevision: Long? = null)
    suspend fun persistClear(canvasKey: String, serverRevision: Long? = null)
    suspend fun replaceFromServerSnapshot(canvasKey: String, strokes: List<Stroke>, serverRevision: Long)
}

@Singleton
class RoomCanvasPersistenceStore @Inject constructor(
    private val drawingRepository: DrawingRepository
) : CanvasPersistenceStore {
    override suspend fun loadCanvasState(canvasKey: String): CanvasState =
        drawingRepository.canvasState(canvasKey).first()

    override suspend fun loadToolState(canvasKey: String): ToolState =
        drawingRepository.toolState(canvasKey).first()

    override suspend fun persistLocalCommittedStroke(canvasKey: String, stroke: Stroke): Long =
        drawingRepository.persistLocalCommittedStroke(canvasKey, stroke)

    override suspend fun persistRemoteCommittedStroke(canvasKey: String, stroke: Stroke, serverRevision: Long) {
        drawingRepository.persistRemoteCommittedStroke(canvasKey, stroke, serverRevision)
    }

    override suspend fun persistErase(canvasKey: String, strokeId: String, serverRevision: Long?) {
        if (serverRevision == null) {
            drawingRepository.eraseStroke(canvasKey, strokeId)
        } else {
            drawingRepository.applyRemoteDeleteStroke(canvasKey, strokeId, serverRevision)
        }
    }

    override suspend fun persistClear(canvasKey: String, serverRevision: Long?) {
        if (serverRevision == null) {
            drawingRepository.clearCanvas(canvasKey)
        } else {
            drawingRepository.applyRemoteClearCanvas(canvasKey, serverRevision)
        }
    }

    override suspend fun replaceFromServerSnapshot(canvasKey: String, strokes: List<Stroke>, serverRevision: Long) {
        drawingRepository.replaceWithRemoteSnapshot(canvasKey, strokes, serverRevision)
    }
}
