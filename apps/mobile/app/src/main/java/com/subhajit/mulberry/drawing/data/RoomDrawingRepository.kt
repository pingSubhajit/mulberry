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

    override suspend fun startStroke(point: StrokePoint) {
        val metadata = currentMetadata()
        if (metadata.selectedTool != DrawingTool.DRAW) return

        activeStroke.value = strokeBuilder.startStroke(
            point = point,
            brushStyle = BrushStyle(
                colorArgb = metadata.selectedColorArgb,
                width = metadata.selectedWidth
            )
        )
    }

    override suspend fun appendPoint(point: StrokePoint) {
        val active = activeStroke.value ?: return
        activeStroke.value = strokeBuilder.appendPoint(active, point)
    }

    override suspend fun finishStroke() {
        val committedStroke = strokeBuilder.finishStroke(activeStroke.value ?: return) ?: return
        val now = System.currentTimeMillis()

        database.withTransaction {
            val metadata = currentMetadata()
            val nextRevision = metadata.revision + 1
            drawingDao.insertStroke(committedStroke.toEntity())
            drawingDao.insertStrokePoints(committedStroke.toPointEntities())
            drawingOperationsDao.insertOperation(
                DrawingOperationEntity(
                    type = DrawingOperationType.ADD_STROKE,
                    strokeId = committedStroke.id,
                    payload = "pointCount=${committedStroke.points.size}",
                    revision = nextRevision,
                    createdAt = now
                )
            )
            if (committedStroke.points.size > 1) {
                drawingOperationsDao.insertOperation(
                    DrawingOperationEntity(
                        type = DrawingOperationType.APPEND_POINTS,
                        strokeId = committedStroke.id,
                        payload = "pointCount=${committedStroke.points.size - 1}",
                        revision = nextRevision,
                        createdAt = now
                    )
                )
            }
            canvasMetadataDao.upsertMetadata(
                metadata.copy(
                    revision = nextRevision,
                    lastModifiedAt = now,
                    selectedColorArgb = committedStroke.colorArgb,
                    selectedWidth = committedStroke.width,
                    selectedTool = DrawingTool.DRAW,
                    isSnapshotDirty = true
                )
            )
        }

        activeStroke.value = null
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
