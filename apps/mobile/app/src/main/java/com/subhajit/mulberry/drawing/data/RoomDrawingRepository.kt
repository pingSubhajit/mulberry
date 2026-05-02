package com.subhajit.mulberry.drawing.data

import androidx.room.withTransaction
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.data.local.CanvasStickerElementDao
import com.subhajit.mulberry.drawing.data.local.CanvasTextElementDao
import com.subhajit.mulberry.drawing.data.local.CanvasStickerElementEntity
import com.subhajit.mulberry.drawing.data.local.CanvasTextElementEntity
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.DrawingOperationEntity
import com.subhajit.mulberry.drawing.data.local.DrawingOperationsDao
import com.subhajit.mulberry.drawing.data.local.DrawingDao
import com.subhajit.mulberry.drawing.data.local.toDomain
import com.subhajit.mulberry.drawing.data.local.toEntity
import com.subhajit.mulberry.drawing.data.local.toPointEntities
import com.subhajit.mulberry.drawing.engine.StrokeBuilder
import com.subhajit.mulberry.drawing.geometry.normalizeStrokeWidth
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasElementKind
import com.subhajit.mulberry.drawing.model.BrushStyle
import com.subhajit.mulberry.drawing.model.CanvasSnapshotState
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.ToolState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class RoomDrawingRepository @Inject constructor(
    private val database: DrawingDatabase,
    private val drawingDao: DrawingDao,
    private val drawingOperationsDao: DrawingOperationsDao,
    private val canvasTextElementDao: CanvasTextElementDao,
    private val canvasStickerElementDao: CanvasStickerElementDao,
    private val canvasMetadataDao: CanvasMetadataDao,
    private val strokeBuilder: StrokeBuilder
) : DrawingRepository {

    private val activeStroke = MutableStateFlow<Stroke?>(null)
    private val zIndexScale = 1000L

    private val metadataFlow = canvasMetadataDao.observeMetadata()
        .map { it ?: CanvasMetadataEntity.default() }

    override val toolState: Flow<ToolState> = metadataFlow.map { metadata ->
        ToolState(
            activeTool = metadata.selectedTool,
            strokeColorArgb = metadata.selectedColorArgb,
            textColorArgb = metadata.selectedTextColorArgb,
            selectedWidth = metadata.selectedWidth
        )
    }

    override val canvasState: Flow<CanvasState> = combine(
        drawingDao.observeStrokeGraphs().map { list -> list.map { it.toDomain() } },
        canvasTextElementDao.observeElements(),
        canvasStickerElementDao.observeElements(),
        activeStroke,
        metadataFlow
    ) { strokes, textEntities, stickerEntities, active, metadata ->
        val elements: List<CanvasElement> = mergeElementsByZIndex(textEntities, stickerEntities)
        CanvasState(
            strokes = strokes,
            elements = elements,
            activeStroke = active,
            isEmpty = strokes.isEmpty() && elements.isEmpty() && active == null,
            revision = metadata.revision,
            snapshotState = CanvasSnapshotState(
                isDirty = metadata.isSnapshotDirty,
                lastSnapshotRevision = metadata.lastSnapshotRevision,
                cachedImagePath = metadata.cachedImagePath
            )
        )
    }

    private fun mergeElementsByZIndex(
        textEntities: List<CanvasTextElementEntity>,
        stickerEntities: List<CanvasStickerElementEntity>
    ): List<CanvasElement> {
        if (textEntities.isEmpty() && stickerEntities.isEmpty()) return emptyList()
        val merged = ArrayList<CanvasElement>(textEntities.size + stickerEntities.size)
        var i = 0
        var j = 0
        while (i < textEntities.size || j < stickerEntities.size) {
            val nextText = textEntities.getOrNull(i)
            val nextSticker = stickerEntities.getOrNull(j)
            if (nextText == null) {
                merged.add(nextSticker!!.toDomain())
                j += 1
                continue
            }
            if (nextSticker == null) {
                merged.add(nextText.toDomain())
                i += 1
                continue
            }
            if (nextText.zIndex <= nextSticker.zIndex) {
                merged.add(nextText.toDomain())
                i += 1
            } else {
                merged.add(nextSticker.toDomain())
                j += 1
            }
        }
        return merged
    }

    override suspend fun startStroke(point: StrokePoint): Stroke? {
        val metadata = currentMetadata()
        if (metadata.selectedTool != DrawingTool.DRAW) return null

        val stroke = strokeBuilder.startStroke(
            point = point,
            brushStyle = BrushStyle(
                colorArgb = metadata.selectedColorArgb,
                width = normalizeStrokeWidth(
                    width = metadata.selectedWidth,
                    surfaceWidth = metadata.canvasWidthPx,
                    surfaceHeight = metadata.canvasHeightPx
                )
            )
        )
        activeStroke.value = stroke
        return stroke
    }

    override suspend fun appendPoint(point: StrokePoint): Stroke? {
        val active = activeStroke.value ?: return null
        val next = strokeBuilder.appendPoint(
            stroke = active,
            point = point,
            samePointThreshold = normalizeStrokeWidth(
                width = 0.5f,
                surfaceWidth = currentMetadata().canvasWidthPx,
                surfaceHeight = currentMetadata().canvasHeightPx
            )
        )
        activeStroke.value = next
        return next
    }

    override suspend fun finishStroke(): Stroke? {
        val committedStroke = strokeBuilder.finishStroke(activeStroke.value ?: return null) ?: return null
        persistLocalCommittedStroke(committedStroke)
        activeStroke.value = null
        return committedStroke
    }

    override suspend fun setBrushColor(colorArgb: Long) {
        val metadata = currentMetadata()
        canvasMetadataDao.upsertMetadata(
            metadata.copy(
                selectedColorArgb = colorArgb,
                lastModifiedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun setTextColor(colorArgb: Long) {
        val metadata = currentMetadata()
        canvasMetadataDao.upsertMetadata(
            metadata.copy(
                selectedTextColorArgb = colorArgb,
                lastModifiedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun setBrushWidth(width: Float) {
        val metadata = currentMetadata()
        canvasMetadataDao.upsertMetadata(
            metadata.copy(
                selectedWidth = width.coerceIn(DrawingDefaults.MIN_WIDTH, DrawingDefaults.MAX_WIDTH),
                lastModifiedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun setTool(tool: DrawingTool) {
        val metadata = currentMetadata()
        canvasMetadataDao.upsertMetadata(
            metadata.copy(
                selectedTool = tool,
                lastModifiedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun setCanvasViewport(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return

        val metadata = currentMetadata()
        if (metadata.canvasWidthPx == widthPx && metadata.canvasHeightPx == heightPx) return

        canvasMetadataDao.upsertMetadata(
            metadata.copy(
                canvasWidthPx = widthPx,
                canvasHeightPx = heightPx,
                lastModifiedAt = System.currentTimeMillis(),
                isSnapshotDirty = true
            )
        )
    }

    override suspend fun eraseStroke(strokeId: String) {
        activeStroke.value = null
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            val deleted = drawingDao.deleteStrokeById(strokeId)
            if (deleted == 0) return@withTransaction

            val nextRevision = metadata.revision + 1
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.DELETE_STROKE,
                    strokeId = strokeId,
                    payload = "{}",
                    revision = nextRevision,
                    createdAt = now
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = nextRevision,
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun clearCanvas() {
        activeStroke.value = null
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            val nextRevision = metadata.revision + 1
            drawingDao.clearStrokes()
            canvasTextElementDao.clear()
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.CLEAR_CANVAS,
                    payload = "{}",
                    revision = nextRevision,
                    createdAt = now
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = nextRevision,
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun applyRemoteAddStroke(stroke: Stroke, serverRevision: Long) {
        persistRemoteCommittedStroke(stroke, serverRevision)
    }

    override suspend fun persistLocalCommittedStroke(stroke: Stroke): Long {
        val now = System.currentTimeMillis()
        var nextRevision = 0L
        database.withTransaction {
            val metadata = currentMetadata()
            nextRevision = metadata.revision + 1
            drawingDao.insertStroke(stroke.toEntity())
            drawingDao.deleteStrokePoints(stroke.id)
            drawingDao.insertStrokePoints(stroke.toPointEntities())
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.ADD_STROKE,
                    strokeId = stroke.id,
                    payload = stroke.addStrokePayloadJson(),
                    revision = nextRevision,
                    createdAt = now
                )
            )
            if (stroke.points.size > 1) {
                drawingOperationsDao.insertOperation(
                    DrawingOperationEntity(
                        type = DrawingOperationType.APPEND_POINTS,
                        strokeId = stroke.id,
                        payload = pointsPayloadJson(stroke.points.drop(1)),
                        revision = nextRevision,
                        createdAt = now
                    )
                )
            }
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = nextRevision,
                    lastModifiedAt = now,
                    selectedColorArgb = stroke.colorArgb,
                    selectedTool = DrawingTool.DRAW,
                    isSnapshotDirty = true
                )
            )
        }
        return nextRevision
    }

    override suspend fun persistRemoteCommittedStroke(stroke: Stroke, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            drawingDao.insertStroke(stroke.toEntity())
            drawingDao.deleteStrokePoints(stroke.id)
            drawingDao.insertStrokePoints(stroke.toPointEntities())
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.ADD_STROKE,
                    strokeId = stroke.id,
                    payload = stroke.addStrokePayloadJson(),
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            if (stroke.points.size > 1) {
                drawingOperationsDao.insertOperation(
                    DrawingOperationEntity(
                        type = DrawingOperationType.APPEND_POINTS,
                        strokeId = stroke.id,
                        payload = pointsPayloadJson(stroke.points.drop(1)),
                        revision = maxOf(metadata.revision + 1, serverRevision),
                        createdAt = now,
                        serverRevision = serverRevision,
                        syncStatus = "REMOTE_APPLIED"
                    )
                )
            }
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun applyRemoteAppendPoints(
        strokeId: String,
        points: List<StrokePoint>,
        serverRevision: Long
    ) {
        if (points.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            if (!drawingDao.strokeExists(strokeId)) {
                drawingOperationsDao.insertOperation(
                    DrawingOperationEntity(
                        type = DrawingOperationType.APPEND_POINTS,
                        strokeId = strokeId,
                        payload = pointsPayloadJson(points),
                        revision = maxOf(metadata.revision + 1, serverRevision),
                        createdAt = now,
                        serverRevision = serverRevision,
                        syncStatus = "REMOTE_ORPHAN_SKIPPED"
                    )
                )
                canvasMetadataDao.upsertMetadata(
                    metadata.copy(
                        revision = maxOf(metadata.revision, serverRevision),
                        lastModifiedAt = now
                    )
                )
                return@withTransaction
            }
            val startIndex = drawingDao.maxPointIndex(strokeId) + 1
            drawingDao.insertStrokePoints(
                points.mapIndexed { index, point ->
                    com.subhajit.mulberry.drawing.data.local.StrokePointEntity(
                        strokeId = strokeId,
                        pointIndex = startIndex + index,
                        x = point.x,
                        y = point.y
                    )
                }
            )
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.APPEND_POINTS,
                    strokeId = strokeId,
                    payload = pointsPayloadJson(points),
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun applyRemoteFinishStroke(strokeId: String, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.FINISH_STROKE,
                    strokeId = strokeId,
                    payload = "{}",
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun applyRemoteDeleteStroke(strokeId: String, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            drawingDao.deleteStrokeById(strokeId)
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.DELETE_STROKE,
                    strokeId = strokeId,
                    payload = "{}",
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun applyRemoteClearCanvas(serverRevision: Long) {
        activeStroke.value = null
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            drawingDao.clearStrokes()
            canvasTextElementDao.clear()
            canvasStickerElementDao.clear()
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.CLEAR_CANVAS,
                    payload = "{}",
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun replaceWithRemoteSnapshot(
        strokes: List<Stroke>,
        elements: List<CanvasElement>,
        serverRevision: Long
    ) {
        activeStroke.value = null
        val textElements = elements.filterIsInstance<CanvasTextElement>()
        val stickerElements = elements.filterIsInstance<CanvasStickerElement>()
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            drawingDao.clearStrokes()
            canvasTextElementDao.clear()
            canvasStickerElementDao.clear()
            strokes.forEach { stroke ->
                drawingDao.insertStroke(stroke.toEntity())
                drawingDao.deleteStrokePoints(stroke.id)
                drawingDao.insertStrokePoints(stroke.toPointEntities())
            }
            textElements.forEachIndexed { index, element ->
                canvasTextElementDao.upsert(
                    element.toEntity(zIndex = (serverRevision * zIndexScale) + index.toLong())
                )
            }
            stickerElements.forEachIndexed { index, element ->
                canvasStickerElementDao.upsert(
                    element.toEntity(
                        zIndex = (serverRevision * zIndexScale) + (textElements.size + index).toLong()
                    )
                )
            }
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.CLEAR_CANVAS,
                    payload = """{"snapshotRestore":true,"strokeCount":${strokes.size},"textElementCount":${textElements.size},"stickerElementCount":${stickerElements.size}}""",
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_SNAPSHOT_RESTORED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = serverRevision,
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun upsertLocalTextElement(element: CanvasTextElement): Long {
        val now = System.currentTimeMillis()
        var nextRevision = 0L
        database.withTransaction {
            val metadata = currentMetadata()
            nextRevision = metadata.revision + 1
            canvasTextElementDao.upsert(element.toEntity(zIndex = nextRevision * zIndexScale))
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.UPDATE_TEXT_ELEMENT,
                    strokeId = element.id,
                    payload = element.textElementPayloadJson(),
                    revision = nextRevision,
                    createdAt = now
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = nextRevision,
                    lastModifiedAt = now,
                    selectedTextColorArgb = element.colorArgb,
                    isSnapshotDirty = true
                )
            )
        }
        return nextRevision
    }

    override suspend fun deleteLocalTextElement(elementId: String): Long {
        val now = System.currentTimeMillis()
        var nextRevision = 0L
        database.withTransaction {
            val metadata = currentMetadata()
            val deleted = canvasTextElementDao.deleteById(elementId)
            if (deleted == 0) {
                nextRevision = metadata.revision
                return@withTransaction
            }
            nextRevision = metadata.revision + 1
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.DELETE_TEXT_ELEMENT,
                    strokeId = elementId,
                    payload = "{}",
                    revision = nextRevision,
                    createdAt = now
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = nextRevision,
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
        return nextRevision
    }

    override suspend fun applyRemoteAddOrUpdateTextElement(element: CanvasTextElement, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            canvasTextElementDao.upsert(element.toEntity(zIndex = serverRevision * zIndexScale))
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.UPDATE_TEXT_ELEMENT,
                    strokeId = element.id,
                    payload = element.textElementPayloadJson(),
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun applyRemoteDeleteTextElement(elementId: String, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            canvasTextElementDao.deleteById(elementId)
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.DELETE_TEXT_ELEMENT,
                    strokeId = elementId,
                    payload = "{}",
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun upsertLocalStickerElement(element: CanvasStickerElement): Long {
        val now = System.currentTimeMillis()
        var nextRevision = 0L
        database.withTransaction {
            val metadata = currentMetadata()
            nextRevision = metadata.revision + 1
            canvasStickerElementDao.upsert(element.toEntity(zIndex = nextRevision * zIndexScale))
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.UPDATE_STICKER_ELEMENT,
                    strokeId = element.id,
                    payload = element.stickerElementPayloadJson(),
                    revision = nextRevision,
                    createdAt = now
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = nextRevision,
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
        return nextRevision
    }

    override suspend fun deleteLocalStickerElement(elementId: String): Long {
        val now = System.currentTimeMillis()
        var nextRevision = 0L
        database.withTransaction {
            val metadata = currentMetadata()
            val deleted = canvasStickerElementDao.deleteById(elementId)
            if (deleted == 0) {
                nextRevision = metadata.revision
                return@withTransaction
            }
            nextRevision = metadata.revision + 1
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.DELETE_STICKER_ELEMENT,
                    strokeId = elementId,
                    payload = "{}",
                    revision = nextRevision,
                    createdAt = now
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = nextRevision,
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
        return nextRevision
    }

    override suspend fun applyRemoteAddOrUpdateStickerElement(element: CanvasStickerElement, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            canvasStickerElementDao.upsert(element.toEntity(zIndex = serverRevision * zIndexScale))
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.UPDATE_STICKER_ELEMENT,
                    strokeId = element.id,
                    payload = element.stickerElementPayloadJson(),
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun applyRemoteDeleteStickerElement(elementId: String, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            canvasStickerElementDao.deleteById(elementId)
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.DELETE_STICKER_ELEMENT,
                    strokeId = elementId,
                    payload = "{}",
                    revision = maxOf(metadata.revision + 1, serverRevision),
                    createdAt = now,
                    serverRevision = serverRevision,
                    syncStatus = "REMOTE_APPLIED"
                )
            )
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = maxOf(metadata.revision, serverRevision),
                    lastModifiedAt = now,
                    isSnapshotDirty = true
                )
            )
        }
    }

    override suspend fun resetAllDrawingState() {
        activeStroke.value = null
        database.withTransaction {
            drawingDao.clearStrokes()
            drawingOperationsDao.clearOperations()
            canvasTextElementDao.clear()
            canvasStickerElementDao.clear()
            canvasMetadataDao.upsertMetadata(CanvasMetadataEntity.default())
        }
    }

    private suspend fun currentMetadata(): CanvasMetadataEntity =
        canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
}

private fun Stroke.addStrokePayloadJson(): String {
    val firstPoint = points.firstOrNull()
    return if (firstPoint == null) {
        "{}"
    } else {
        """{"id":"$id","colorArgb":$colorArgb,"width":$width,"createdAt":$createdAt,"firstPoint":{"x":${firstPoint.x},"y":${firstPoint.y}}}"""
    }
}

private fun pointsPayloadJson(points: List<StrokePoint>): String =
    points.joinToString(
        prefix = """{"points":[""",
        postfix = "]}",
        separator = ","
    ) { point ->
        """{"x":${point.x},"y":${point.y}}"""
    }

private fun CanvasTextElement.textElementPayloadJson(): String {
    return """{"id":"$id","text":${text.jsonQuoted()},"createdAt":$createdAt,"center":{"x":${center.x},"y":${center.y}},"rotationRad":$rotationRad,"scale":$scale,"boxWidth":$boxWidth,"colorArgb":$colorArgb,"backgroundPillEnabled":$backgroundPillEnabled,"font":"${font.name}","alignment":"${alignment.name}"}"""
}

private fun CanvasStickerElement.stickerElementPayloadJson(): String {
    return """{"id":"$id","createdAt":$createdAt,"center":{"x":${center.x},"y":${center.y}},"rotationRad":$rotationRad,"scale":$scale,"packKey":${packKey.jsonQuoted()},"packVersion":$packVersion,"stickerId":${stickerId.jsonQuoted()}}"""
}

private fun String.jsonQuoted(): String =
    buildString(length + 2) {
        append('"')
        for (ch in this@jsonQuoted) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
