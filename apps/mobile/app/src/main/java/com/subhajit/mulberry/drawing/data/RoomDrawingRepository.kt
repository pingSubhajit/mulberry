package com.subhajit.mulberry.drawing.data

import androidx.room.withTransaction
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.DrawingOperationEntity
import com.subhajit.mulberry.drawing.data.local.DrawingOperationsDao
import com.subhajit.mulberry.drawing.data.local.DrawingDao
import com.subhajit.mulberry.drawing.data.local.strokeKey
import com.subhajit.mulberry.drawing.data.local.toDomain
import com.subhajit.mulberry.drawing.data.local.toEntity
import com.subhajit.mulberry.drawing.data.local.toPointEntities
import com.subhajit.mulberry.drawing.engine.StrokeBuilder
import com.subhajit.mulberry.drawing.geometry.normalizeStrokeWidth
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
import kotlinx.coroutines.flow.update

@Singleton
class RoomDrawingRepository @Inject constructor(
    private val database: DrawingDatabase,
    private val drawingDao: DrawingDao,
    private val drawingOperationsDao: DrawingOperationsDao,
    private val canvasMetadataDao: CanvasMetadataDao,
    private val strokeBuilder: StrokeBuilder
) : DrawingRepository {

    private val activeStrokeByCanvasKey = MutableStateFlow<Map<String, Stroke?>>(emptyMap())

    private fun metadataFlow(canvasKey: String): Flow<CanvasMetadataEntity> =
        canvasMetadataDao.observeMetadata(canvasKey).map { it ?: CanvasMetadataEntity.default(canvasKey) }

    override fun toolState(canvasKey: String): Flow<ToolState> = metadataFlow(canvasKey).map { metadata ->
        ToolState(
            activeTool = metadata.selectedTool,
            selectedColorArgb = metadata.selectedColorArgb,
            selectedWidth = metadata.selectedWidth
        )
    }

    override fun canvasState(canvasKey: String): Flow<CanvasState> = combine(
        drawingDao.observeStrokeGraphs(canvasKey).map { list -> list.map { it.toDomain() } },
        activeStrokeByCanvasKey.map { it[canvasKey] },
        metadataFlow(canvasKey)
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

    override suspend fun startStroke(canvasKey: String, point: StrokePoint): Stroke? {
        val metadata = currentMetadata(canvasKey)
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
        activeStrokeByCanvasKey.update { current -> current + (canvasKey to stroke) }
        return stroke
    }

    override suspend fun appendPoint(canvasKey: String, point: StrokePoint): Stroke? {
        val active = activeStrokeByCanvasKey.value[canvasKey] ?: return null
        val next = strokeBuilder.appendPoint(
            stroke = active,
            point = point,
            samePointThreshold = normalizeStrokeWidth(
                width = 0.5f,
                surfaceWidth = currentMetadata(canvasKey).canvasWidthPx,
                surfaceHeight = currentMetadata(canvasKey).canvasHeightPx
            )
        )
        activeStrokeByCanvasKey.update { current -> current + (canvasKey to next) }
        return next
    }

    override suspend fun finishStroke(canvasKey: String): Stroke? {
        val committedStroke =
            strokeBuilder.finishStroke(activeStrokeByCanvasKey.value[canvasKey] ?: return null)
                ?: return null
        persistLocalCommittedStroke(canvasKey, committedStroke)
        activeStrokeByCanvasKey.update { current -> current + (canvasKey to null) }
        return committedStroke
    }

    override suspend fun setBrushColor(canvasKey: String, colorArgb: Long) {
        val metadata = currentMetadata(canvasKey)
        canvasMetadataDao.upsertMetadata(
            metadata.copy(
                selectedColorArgb = colorArgb,
                lastModifiedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun setBrushWidth(canvasKey: String, width: Float) {
        val metadata = currentMetadata(canvasKey)
        canvasMetadataDao.upsertMetadata(
            metadata.copy(
                selectedWidth = width.coerceIn(DrawingDefaults.MIN_WIDTH, DrawingDefaults.MAX_WIDTH),
                lastModifiedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun setTool(canvasKey: String, tool: DrawingTool) {
        val metadata = currentMetadata(canvasKey)
        canvasMetadataDao.upsertMetadata(
            metadata.copy(
                selectedTool = tool,
                lastModifiedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun setCanvasViewport(canvasKey: String, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return

        val metadata = currentMetadata(canvasKey)
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

    override suspend fun eraseStroke(canvasKey: String, strokeId: String) {
        activeStrokeByCanvasKey.update { current -> current + (canvasKey to null) }
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            val deleted = drawingDao.deleteStrokeByKey(strokeKey(canvasKey, strokeId))
            if (deleted == 0) return@withTransaction

            val nextRevision = metadata.revision + 1
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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

    override suspend fun clearCanvas(canvasKey: String) {
        activeStrokeByCanvasKey.update { current -> current + (canvasKey to null) }
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            val nextRevision = metadata.revision + 1
            drawingDao.clearStrokes(canvasKey)
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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

    override suspend fun applyRemoteAddStroke(canvasKey: String, stroke: Stroke, serverRevision: Long) {
        persistRemoteCommittedStroke(canvasKey, stroke, serverRevision)
    }

    override suspend fun persistLocalCommittedStroke(canvasKey: String, stroke: Stroke): Long {
        val now = System.currentTimeMillis()
        var nextRevision = 0L
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            nextRevision = metadata.revision + 1
            drawingDao.insertStroke(stroke.toEntity(canvasKey))
            drawingDao.deleteStrokePoints(strokeKey(canvasKey, stroke.id))
            drawingDao.insertStrokePoints(stroke.toPointEntities(canvasKey))
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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
                        canvasKey = canvasKey,
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

    override suspend fun persistRemoteCommittedStroke(canvasKey: String, stroke: Stroke, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            drawingDao.insertStroke(stroke.toEntity(canvasKey))
            drawingDao.deleteStrokePoints(strokeKey(canvasKey, stroke.id))
            drawingDao.insertStrokePoints(stroke.toPointEntities(canvasKey))
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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
                        canvasKey = canvasKey,
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
        canvasKey: String,
        strokeId: String,
        points: List<StrokePoint>,
        serverRevision: Long
    ) {
        if (points.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            val strokeKey = strokeKey(canvasKey, strokeId)
            if (!drawingDao.strokeExists(strokeKey)) {
                drawingOperationsDao.insertOperation(
                    DrawingOperationEntity(
                        canvasKey = canvasKey,
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
            val startIndex = drawingDao.maxPointIndex(strokeKey) + 1
            drawingDao.insertStrokePoints(
                points.mapIndexed { index, point ->
                    com.subhajit.mulberry.drawing.data.local.StrokePointEntity(
                        strokeKey = strokeKey,
                        pointIndex = startIndex + index,
                        x = point.x,
                        y = point.y
                    )
                }
            )
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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

    override suspend fun applyRemoteFinishStroke(canvasKey: String, strokeId: String, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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

    override suspend fun applyRemoteDeleteStroke(canvasKey: String, strokeId: String, serverRevision: Long) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            drawingDao.deleteStrokeByKey(strokeKey(canvasKey, strokeId))
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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

    override suspend fun applyRemoteClearCanvas(canvasKey: String, serverRevision: Long) {
        activeStrokeByCanvasKey.update { current -> current + (canvasKey to null) }
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            drawingDao.clearStrokes(canvasKey)
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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

    override suspend fun replaceWithRemoteSnapshot(canvasKey: String, strokes: List<Stroke>, serverRevision: Long) {
        activeStrokeByCanvasKey.update { current -> current + (canvasKey to null) }
        val now = System.currentTimeMillis()
        database.withTransaction {
            val metadata = currentMetadata(canvasKey)
            drawingDao.clearStrokes(canvasKey)
            strokes.forEach { stroke ->
                drawingDao.insertStroke(stroke.toEntity(canvasKey))
                drawingDao.deleteStrokePoints(strokeKey(canvasKey, stroke.id))
                drawingDao.insertStrokePoints(stroke.toPointEntities(canvasKey))
            }
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    canvasKey = canvasKey,
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
        activeStrokeByCanvasKey.value = emptyMap()
        database.withTransaction {
            drawingDao.clearAllStrokes()
            drawingOperationsDao.clearAllOperations()
            canvasMetadataDao.clearAllMetadata()
        }
    }

    private suspend fun currentMetadata(canvasKey: String): CanvasMetadataEntity =
        canvasMetadataDao.getMetadata(canvasKey) ?: CanvasMetadataEntity.default(canvasKey)
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
