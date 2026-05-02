package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.ToolState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface CanvasPersistenceStore {
    suspend fun loadCanvasState(): CanvasState
    suspend fun loadToolState(): ToolState
    suspend fun persistLocalCommittedStroke(stroke: Stroke): Long
    suspend fun persistRemoteCommittedStroke(stroke: Stroke, serverRevision: Long)
    suspend fun persistErase(strokeId: String, serverRevision: Long? = null)
    suspend fun persistClear(serverRevision: Long? = null)
    suspend fun replaceFromServerSnapshot(
        strokes: List<Stroke>,
        elements: List<CanvasElement>,
        serverRevision: Long
    )

    suspend fun persistUpsertTextElement(element: CanvasTextElement): Long
    suspend fun persistDeleteTextElement(elementId: String): Long
    suspend fun persistRemoteUpsertTextElement(element: CanvasTextElement, serverRevision: Long)
    suspend fun persistRemoteDeleteTextElement(elementId: String, serverRevision: Long)

    suspend fun persistUpsertStickerElement(element: CanvasStickerElement): Long
    suspend fun persistDeleteStickerElement(elementId: String): Long
    suspend fun persistRemoteUpsertStickerElement(element: CanvasStickerElement, serverRevision: Long)
    suspend fun persistRemoteDeleteStickerElement(elementId: String, serverRevision: Long)
}

@Singleton
class RoomCanvasPersistenceStore @Inject constructor(
    private val drawingRepository: DrawingRepository
) : CanvasPersistenceStore {
    override suspend fun loadCanvasState(): CanvasState =
        drawingRepository.canvasState.first()

    override suspend fun loadToolState(): ToolState =
        drawingRepository.toolState.first()

    override suspend fun persistLocalCommittedStroke(stroke: Stroke): Long =
        drawingRepository.persistLocalCommittedStroke(stroke)

    override suspend fun persistRemoteCommittedStroke(stroke: Stroke, serverRevision: Long) {
        drawingRepository.persistRemoteCommittedStroke(stroke, serverRevision)
    }

    override suspend fun persistErase(strokeId: String, serverRevision: Long?) {
        if (serverRevision == null) {
            drawingRepository.eraseStroke(strokeId)
        } else {
            drawingRepository.applyRemoteDeleteStroke(strokeId, serverRevision)
        }
    }

    override suspend fun persistClear(serverRevision: Long?) {
        if (serverRevision == null) {
            drawingRepository.clearCanvas()
        } else {
            drawingRepository.applyRemoteClearCanvas(serverRevision)
        }
    }

    override suspend fun replaceFromServerSnapshot(
        strokes: List<Stroke>,
        elements: List<CanvasElement>,
        serverRevision: Long
    ) {
        drawingRepository.replaceWithRemoteSnapshot(strokes, elements, serverRevision)
    }

    override suspend fun persistUpsertTextElement(element: CanvasTextElement): Long =
        drawingRepository.upsertLocalTextElement(element)

    override suspend fun persistDeleteTextElement(elementId: String): Long =
        drawingRepository.deleteLocalTextElement(elementId)

    override suspend fun persistRemoteUpsertTextElement(element: CanvasTextElement, serverRevision: Long) {
        drawingRepository.applyRemoteAddOrUpdateTextElement(element, serverRevision)
    }

    override suspend fun persistRemoteDeleteTextElement(elementId: String, serverRevision: Long) {
        drawingRepository.applyRemoteDeleteTextElement(elementId, serverRevision)
    }

    override suspend fun persistUpsertStickerElement(element: CanvasStickerElement): Long =
        drawingRepository.upsertLocalStickerElement(element)

    override suspend fun persistDeleteStickerElement(elementId: String): Long =
        drawingRepository.deleteLocalStickerElement(elementId)

    override suspend fun persistRemoteUpsertStickerElement(element: CanvasStickerElement, serverRevision: Long) {
        drawingRepository.applyRemoteAddOrUpdateStickerElement(element, serverRevision)
    }

    override suspend fun persistRemoteDeleteStickerElement(elementId: String, serverRevision: Long) {
        drawingRepository.applyRemoteDeleteStickerElement(elementId, serverRevision)
    }
}
