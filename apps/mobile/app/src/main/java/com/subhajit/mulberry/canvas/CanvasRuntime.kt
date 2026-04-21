package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.engine.StrokeBuilder
import com.subhajit.mulberry.drawing.engine.StrokeHitTester
import com.subhajit.mulberry.drawing.model.BrushStyle
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.sync.CanvasSyncOperation
import com.subhajit.mulberry.sync.ServerCanvasOperation
import com.subhajit.mulberry.sync.SyncOperationPayload
import com.subhajit.mulberry.sync.SyncState
import com.subhajit.mulberry.sync.newClientOperation
import com.subhajit.mulberry.sync.toAddStrokePayload
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface CanvasRuntime {
    val renderState: StateFlow<CanvasRenderState>
    val outboundOperations: Flow<CanvasSyncOperation>

    fun start(pairSessionId: String, userId: String)
    fun stop()
    fun submit(event: CanvasRuntimeEvent)
    fun setSyncState(syncState: SyncState)
}

@Singleton
class DefaultCanvasRuntime @Inject constructor(
    private val persistenceStore: CanvasPersistenceStore,
    private val drawingRepository: DrawingRepository,
    private val strokeBuilder: StrokeBuilder,
    private val strokeHitTester: StrokeHitTester,
    @ApplicationScope private val applicationScope: CoroutineScope
) : CanvasRuntime {
    private val events = Channel<CanvasRuntimeEvent>(Channel.UNLIMITED)
    private val outbound = Channel<CanvasSyncOperation>(Channel.UNLIMITED)

    override val outboundOperations: Flow<CanvasSyncOperation> = outbound.receiveAsFlow()

    private val _renderState = MutableStateFlow(CanvasRenderState())
    override val renderState: StateFlow<CanvasRenderState> = _renderState

    private var activePairSessionId: String? = null
    private var activeUserId: String? = null
    private var activeAppendHz = DEFAULT_APPEND_HZ
    private var lastAppendFlushAt = 0L
    private val pendingAppendPoints = mutableListOf<StrokePoint>()

    init {
        applicationScope.launch {
            for (event in events) {
                handleEvent(event)
            }
        }
        applicationScope.launch {
            drawingRepository.toolState.collect { toolState ->
                _renderState.update { it.copy(toolState = toolState) }
            }
        }
    }

    override fun start(pairSessionId: String, userId: String) {
        activePairSessionId = pairSessionId
        activeUserId = userId
        applicationScope.launch {
            val canvasState = persistenceStore.loadCanvasState()
            val toolState = persistenceStore.loadToolState()
            _renderState.value = _renderState.value.copy(
                committedStrokes = canvasState.strokes,
                localActiveStroke = null,
                remoteActiveStrokes = emptyMap(),
                revision = canvasState.revision,
                snapshotState = canvasState.snapshotState,
                toolState = toolState,
                cacheToken = _renderState.value.cacheToken + 1
            )
        }
    }

    override fun stop() {
        activePairSessionId = null
        activeUserId = null
        pendingAppendPoints.clear()
        _renderState.update {
            it.copy(
                localActiveStroke = null,
                remoteActiveStrokes = emptyMap(),
                syncState = SyncState.Disconnected
            )
        }
    }

    override fun submit(event: CanvasRuntimeEvent) {
        events.trySend(event)
    }

    override fun setSyncState(syncState: SyncState) {
        _renderState.update { it.copy(syncState = syncState) }
    }

    private suspend fun handleEvent(event: CanvasRuntimeEvent) {
        when (event) {
            is CanvasRuntimeEvent.LocalPress -> handleLocalPress(event.point)
            is CanvasRuntimeEvent.LocalDrag -> handleLocalDrag(event.point)
            CanvasRuntimeEvent.LocalRelease -> handleLocalRelease()
            is CanvasRuntimeEvent.EraseAt -> handleEraseAt(event.point)
            CanvasRuntimeEvent.ClearCanvas -> handleClearCanvas()
            is CanvasRuntimeEvent.RemoteOperation -> applyRemoteOperation(event.operation)
            is CanvasRuntimeEvent.RemoteBatch -> event.operations.forEach { applyRemoteOperation(it) }
            is CanvasRuntimeEvent.RecoverySnapshot -> handleRecoverySnapshot(event)
            is CanvasRuntimeEvent.FlowControl -> handleFlowControl(event)
        }
    }

    private suspend fun handleLocalPress(point: StrokePoint) {
        val toolState = _renderState.value.toolState
        if (toolState.activeTool != DrawingTool.DRAW) return
        val stroke = strokeBuilder.startStroke(
            point = point,
            brushStyle = BrushStyle(
                colorArgb = toolState.selectedColorArgb,
                width = toolState.selectedWidth
            )
        )
        pendingAppendPoints.clear()
        lastAppendFlushAt = System.currentTimeMillis()
        _renderState.update { it.copy(localActiveStroke = stroke) }
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.ADD_STROKE,
                strokeId = stroke.id,
                payload = stroke.toAddStrokePayload()
            )
        )
    }

    private suspend fun handleLocalDrag(point: StrokePoint) {
        val active = _renderState.value.localActiveStroke ?: return
        val next = strokeBuilder.appendPoint(active, point)
        if (next.points.size == active.points.size) return
        _renderState.update { it.copy(localActiveStroke = next) }
        pendingAppendPoints.add(point)
        flushAppendPointsIfNeeded(force = false)
    }

    private suspend fun handleLocalRelease() {
        val active = _renderState.value.localActiveStroke ?: return
        val finished = strokeBuilder.finishStroke(active) ?: return
        flushAppendPointsIfNeeded(force = true)
        persistenceStore.persistLocalCommittedStroke(finished)
        _renderState.update {
            it.copy(
                committedStrokes = it.committedStrokes + finished,
                localActiveStroke = null,
                cacheToken = it.cacheToken + 1
            )
        }
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.FINISH_STROKE,
                strokeId = finished.id,
                payload = SyncOperationPayload.FinishStroke
            )
        )
    }

    private suspend fun handleEraseAt(point: StrokePoint) {
        if (_renderState.value.toolState.activeTool != DrawingTool.ERASE) return
        val stroke = strokeHitTester.findStrokeHit(
            strokes = _renderState.value.committedStrokes,
            point = point
        ) ?: return
        persistenceStore.persistErase(stroke.id)
        _renderState.update {
            it.copy(
                committedStrokes = it.committedStrokes.filterNot { committed ->
                    committed.id == stroke.id
                },
                remoteActiveStrokes = it.remoteActiveStrokes - stroke.id,
                cacheToken = it.cacheToken + 1
            )
        }
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.DELETE_STROKE,
                strokeId = stroke.id,
                payload = SyncOperationPayload.DeleteStroke
            )
        )
    }

    private suspend fun handleClearCanvas() {
        persistenceStore.persistClear()
        _renderState.update {
            it.copy(
                committedStrokes = emptyList(),
                localActiveStroke = null,
                remoteActiveStrokes = emptyMap(),
                cacheToken = it.cacheToken + 1
            )
        }
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.CLEAR_CANVAS,
                strokeId = null,
                payload = SyncOperationPayload.ClearCanvas
            )
        )
    }

    private suspend fun applyRemoteOperation(operation: ServerCanvasOperation) {
        when (operation.payload) {
            is SyncOperationPayload.AddStroke -> {
                val payload = operation.payload
                val stroke = Stroke(
                    id = payload.id,
                    colorArgb = payload.colorArgb,
                    width = payload.width,
                    points = listOf(payload.firstPoint),
                    createdAt = payload.createdAt
                )
                _renderState.update {
                    it.copy(remoteActiveStrokes = it.remoteActiveStrokes + (stroke.id to stroke))
                }
            }
            is SyncOperationPayload.AppendPoints -> {
                val strokeId = operation.strokeId ?: return
                val active = _renderState.value.remoteActiveStrokes[strokeId] ?: return
                val updated = active.copy(points = active.points + operation.payload.points)
                _renderState.update {
                    it.copy(remoteActiveStrokes = it.remoteActiveStrokes + (strokeId to updated))
                }
            }
            SyncOperationPayload.FinishStroke -> {
                val strokeId = operation.strokeId ?: return
                val finished = _renderState.value.remoteActiveStrokes[strokeId] ?: return
                persistenceStore.persistRemoteCommittedStroke(finished, operation.serverRevision)
                _renderState.update {
                    it.copy(
                        committedStrokes = it.committedStrokes
                            .filterNot { stroke -> stroke.id == strokeId } + finished,
                        remoteActiveStrokes = it.remoteActiveStrokes - strokeId,
                        revision = maxOf(it.revision, operation.serverRevision),
                        cacheToken = it.cacheToken + 1
                    )
                }
            }
            SyncOperationPayload.DeleteStroke -> {
                val strokeId = operation.strokeId ?: return
                persistenceStore.persistErase(strokeId, operation.serverRevision)
                _renderState.update {
                    it.copy(
                        committedStrokes = it.committedStrokes.filterNot { stroke ->
                            stroke.id == strokeId
                        },
                        remoteActiveStrokes = it.remoteActiveStrokes - strokeId,
                        revision = maxOf(it.revision, operation.serverRevision),
                        cacheToken = it.cacheToken + 1
                    )
                }
            }
            SyncOperationPayload.ClearCanvas -> {
                persistenceStore.persistClear(operation.serverRevision)
                _renderState.update {
                    it.copy(
                        committedStrokes = emptyList(),
                        localActiveStroke = null,
                        remoteActiveStrokes = emptyMap(),
                        revision = maxOf(it.revision, operation.serverRevision),
                        cacheToken = it.cacheToken + 1
                    )
                }
            }
        }
    }

    private suspend fun handleRecoverySnapshot(event: CanvasRuntimeEvent.RecoverySnapshot) {
        persistenceStore.replaceFromServerSnapshot(event.strokes, event.serverRevision)
        _renderState.update {
            it.copy(
                committedStrokes = event.strokes,
                localActiveStroke = null,
                remoteActiveStrokes = emptyMap(),
                revision = event.serverRevision,
                cacheToken = it.cacheToken + 1
            )
        }
    }

    private fun handleFlowControl(event: CanvasRuntimeEvent.FlowControl) {
        activeAppendHz = event.maxAppendHz.coerceIn(MIN_APPEND_HZ, DEFAULT_APPEND_HZ)
    }

    private suspend fun flushAppendPointsIfNeeded(force: Boolean) {
        val strokeId = _renderState.value.localActiveStroke?.id ?: return
        if (pendingAppendPoints.isEmpty()) return
        val now = System.currentTimeMillis()
        val intervalMs = 1_000L / activeAppendHz.coerceAtLeast(1)
        if (!force && now - lastAppendFlushAt < intervalMs) return
        val points = pendingAppendPoints.toList()
        pendingAppendPoints.clear()
        lastAppendFlushAt = now
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.APPEND_POINTS,
                strokeId = strokeId,
                payload = SyncOperationPayload.AppendPoints(points)
            )
        )
    }

    private companion object {
        const val DEFAULT_APPEND_HZ = 30
        const val MIN_APPEND_HZ = 10
    }
}
