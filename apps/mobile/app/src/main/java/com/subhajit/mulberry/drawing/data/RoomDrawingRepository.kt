package com.subhajit.mulberry.drawing.data

import androidx.room.withTransaction
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.DrawingOperationEntity
import com.subhajit.mulberry.drawing.data.local.DrawingOperationsDao
import com.subhajit.mulberry.drawing.data.local.DrawingDao
import com.subhajit.mulberry.drawing.data.local.toDomain
import com.subhajit.mulberry.drawing.data.local.toEntity
import com.subhajit.mulberry.drawing.data.local.toPointEntities
import com.subhajit.mulberry.drawing.engine.StrokeBuilder
import com.subhajit.mulberry.drawing.model.BrushStyle
import com.subhajit.mulberry.drawing.model.CanvasSnapshotState
import com.subhajit.mulberry.drawing.model.CanvasState
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
    private val canvasMetadataDao: CanvasMetadataDao,
    private val strokeBuilder: StrokeBuilder
) : DrawingRepository {

    private val activeStroke = MutableStateFlow<Stroke?>(null)

    private val metadataFlow = canvasMetadataDao.observeMetadata()
        .map { it ?: CanvasMetadataEntity.default() }

    override val toolState: Flow<ToolState> = metadataFlow.map { metadata ->
        ToolState(
            activeTool = metadata.selectedTool,
            selectedColorArgb = metadata.selectedColorArgb,
            selectedWidth = metadata.selectedWidth
        )
    }

    override val canvasState: Flow<CanvasState> = combine(
        drawingDao.observeStrokeGraphs().map { list -> list.map { it.toDomain() } },
        activeStroke,
        metadataFlow
    ) { strokes, active, metadata ->
        CanvasState(
            strokes = strokes,
            activeStroke = active,
            isEmpty = strokes.isEmpty() && active == null,
            revision = metadata.revision,
            snapshotState = CanvasSnapshotState(
                isDirty = metadata.isSnapshotDirty,
                lastSnapshotRevision = metadata.lastSnapshotRevision,
                cachedImagePath = metadata.cachedImagePath
            )
        )
    }

    override suspend fun startStroke(point: StrokePoint): Stroke? {
        val metadata = currentMetadata()
        if (metadata.selectedTool != DrawingTool.DRAW) return null

        val stroke = strokeBuilder.startStroke(
            point = point,
            brushStyle = BrushStyle(
                colorArgb = metadata.selectedColorArgb,
                width = metadata.selectedWidth
            )
        )
        activeStroke.value = stroke
        return stroke
    }

    override suspend fun appendPoint(point: StrokePoint): Stroke? {
        val active = activeStroke.value ?: return null
        val next = strokeBuilder.appendPoint(active, point)
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
                    selectedWidth = stroke.width,
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

    override suspend fun replaceWithRemoteSnapshot(strokes: List<Stroke>, serverRevision: Long) {
        activeStroke.value = null
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata()
            drawingDao.clearStrokes()
            strokes.forEach { stroke ->
                drawingDao.insertStroke(stroke.toEntity())
                drawingDao.deleteStrokePoints(stroke.id)
                drawingDao.insertStrokePoints(stroke.toPointEntities())
            }
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.CLEAR_CANVAS,
                    payload = """{"snapshotRestore":true,"strokeCount":${strokes.size}}""",
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

    override suspend fun resetAllDrawingState() {
        activeStroke.value = null
        database.withTransaction {
            drawingDao.clearStrokes()
            drawingOperationsDao.clearOperations()
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
