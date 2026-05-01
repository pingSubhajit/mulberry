package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.engine.StrokeBuilder
import com.subhajit.mulberry.drawing.engine.StrokeHitTester
import com.subhajit.mulberry.drawing.geometry.denormalizeStrokeWidth
import com.subhajit.mulberry.drawing.geometry.denormalizeToSurface
import com.subhajit.mulberry.drawing.geometry.normalizeStrokeWidth
import com.subhajit.mulberry.drawing.model.CanvasTextElement
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
import com.subhajit.mulberry.sync.toWireJson
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    fun reset()
    fun submit(event: CanvasRuntimeEvent)
    suspend fun submitAndAwait(event: CanvasRuntimeEvent)
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
    private val events = Channel<RuntimeEventEnvelope>(Channel.UNLIMITED)
    private val outbound = Channel<CanvasSyncOperation>(Channel.UNLIMITED)

    override val outboundOperations: Flow<CanvasSyncOperation> = outbound.receiveAsFlow()

    private val _renderState = MutableStateFlow(CanvasRenderState())
    override val renderState: StateFlow<CanvasRenderState> = _renderState

    private var activePairSessionId: String? = null
    private var activeUserId: String? = null
    private var activeAppendHz = DEFAULT_APPEND_HZ
    private var lastAppendFlushAt = 0L
    private val pendingAppendPoints = mutableListOf<StrokePoint>()
    private var canvasViewportWidthPx = 0
    private var canvasViewportHeightPx = 0
    private val undoStack = ArrayDeque<HistoryAction>()
    private val redoStack = ArrayDeque<HistoryAction>()
    private var startLoadJob: Job? = null

    init {
        applicationScope.launch {
            for (envelope in events) {
                runCatching {
                    handleEvent(envelope.event)
                }.onSuccess {
                    envelope.completion?.complete(Unit)
                }.onFailure { error ->
                    envelope.completion?.completeExceptionally(error)
                }
            }
        }
        applicationScope.launch {
            drawingRepository.toolState.collect { toolState ->
                _renderState.update { current ->
                    val next = current.copy(toolState = toolState)
                    next.withUndoRedoAvailability()
                }
            }
        }
    }

    override fun start(pairSessionId: String, userId: String) {
        activePairSessionId = pairSessionId
        activeUserId = userId
        clearHistory()
        startLoadJob?.cancel()
        startLoadJob = applicationScope.launch {
            val canvasState = persistenceStore.loadCanvasState()
            val toolState = persistenceStore.loadToolState()
            _renderState.value = _renderState.value.copy(
                committedStrokes = canvasState.strokes,
                committedTextElements = canvasState.textElements,
                localActiveStroke = null,
                remoteActiveStrokes = emptyMap(),
                revision = canvasState.revision,
                snapshotState = canvasState.snapshotState,
                toolState = toolState,
                cacheToken = _renderState.value.cacheToken + 1
            ).withUndoRedoAvailability()
        }
    }

    override fun stop() {
        activePairSessionId = null
        activeUserId = null
        pendingAppendPoints.clear()
        startLoadJob?.cancel()
        startLoadJob = null
        clearHistory()
        _renderState.update { current ->
            val next = current.copy(
                localActiveStroke = null,
                remoteActiveStrokes = emptyMap(),
                syncState = SyncState.Disconnected
            )
            next.withUndoRedoAvailability()
        }
    }

    override fun reset() {
        activePairSessionId = null
        activeUserId = null
        pendingAppendPoints.clear()
        canvasViewportWidthPx = 0
        canvasViewportHeightPx = 0
        startLoadJob?.cancel()
        startLoadJob = null
        clearHistory()
        _renderState.update { current ->
            CanvasRenderState(
                toolState = current.toolState,
                syncState = SyncState.Disconnected
            ).withUndoRedoAvailability()
        }
    }

    override fun submit(event: CanvasRuntimeEvent) {
        events.trySend(RuntimeEventEnvelope(event))
    }

    override suspend fun submitAndAwait(event: CanvasRuntimeEvent) {
        val completion = CompletableDeferred<Unit>()
        events.send(RuntimeEventEnvelope(event, completion))
        completion.await()
    }

    override fun setSyncState(syncState: SyncState) {
        _renderState.update { current ->
            val next = current.copy(syncState = syncState)
            next.withUndoRedoAvailability()
        }
    }

    private suspend fun handleEvent(event: CanvasRuntimeEvent) {
        // Ensure we have loaded the persisted state for the current session before applying
        // any subsequent events. This avoids races where `start()`'s async load overwrites
        // mutations performed immediately after start.
        startLoadJob?.join()
        when (event) {
            is CanvasRuntimeEvent.CanvasViewportChanged -> {
                canvasViewportWidthPx = event.widthPx
                canvasViewportHeightPx = event.heightPx
            }
            is CanvasRuntimeEvent.LocalPress -> handleLocalPress(event.point)
            is CanvasRuntimeEvent.LocalDrag -> handleLocalDrag(event.point)
            CanvasRuntimeEvent.LocalRelease -> handleLocalRelease()
            is CanvasRuntimeEvent.EraseAt -> handleEraseAt(event.point)
            CanvasRuntimeEvent.ClearCanvas -> handleClearCanvas()
            CanvasRuntimeEvent.Undo -> handleUndo()
            CanvasRuntimeEvent.Redo -> handleRedo()
            is CanvasRuntimeEvent.RemoteOperation -> applyRemoteOperation(event.operation)
            is CanvasRuntimeEvent.RemoteBatch -> handleRecoveryOperations(
                CanvasRuntimeEvent.RecoveryOperations(
                    operations = event.operations,
                    publishAtomically = true
                )
            )
            is CanvasRuntimeEvent.RecoveryOperations -> handleRecoveryOperations(event)
            is CanvasRuntimeEvent.RecoverySnapshot -> handleRecoverySnapshot(event)
            is CanvasRuntimeEvent.FlowControl -> handleFlowControl(event)
            is CanvasRuntimeEvent.AddTextElement -> handleAddTextElement(event.element)
            is CanvasRuntimeEvent.UpdateTextElement -> handleUpdateTextElement(event.element)
            is CanvasRuntimeEvent.DeleteTextElement -> handleDeleteTextElement(event.elementId)
        }
    }

    private suspend fun handleLocalPress(point: StrokePoint) {
        val toolState = _renderState.value.toolState
        if (toolState.activeTool != DrawingTool.DRAW) return
        if (!hasCanvasViewport()) return
        val stroke = strokeBuilder.startStroke(
            point = point,
            brushStyle = BrushStyle(
                colorArgb = toolState.selectedColorArgb,
                width = normalizeCurrentWidth(toolState.selectedWidth)
            )
        )
        pendingAppendPoints.clear()
        lastAppendFlushAt = System.currentTimeMillis()
        _renderState.update { current ->
            val next = current.copy(localActiveStroke = stroke)
            next.withUndoRedoAvailability()
        }
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
        val next = strokeBuilder.appendPoint(
            stroke = active,
            point = point,
            samePointThreshold = currentPointThreshold()
        )
        if (next.points.size == active.points.size) return
        _renderState.update { current ->
            val state = current.copy(localActiveStroke = next)
            state.withUndoRedoAvailability()
        }
        pendingAppendPoints.add(point)
        flushAppendPointsIfNeeded(force = false)
    }

    private suspend fun handleLocalRelease() {
        val active = _renderState.value.localActiveStroke ?: return
        val finished = strokeBuilder.finishStroke(active) ?: return
        flushAppendPointsIfNeeded(force = true)
        persistenceStore.persistLocalCommittedStroke(finished)
        recordLocalAction(HistoryAction.DrawAction(finished))
        _renderState.update {
            val next = it.copy(
                committedStrokes = it.committedStrokes + finished,
                localActiveStroke = null,
                cacheToken = it.cacheToken + 1
            )
            next.withUndoRedoAvailability()
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
        val renderWidth = canvasViewportWidthPx.coerceAtLeast(1).toFloat()
        val renderHeight = canvasViewportHeightPx.coerceAtLeast(1).toFloat()
        val stroke = strokeHitTester.findStrokeHit(
            strokes = _renderState.value.committedStrokes.map { committed ->
                committed.denormalizeToSurface(renderWidth, renderHeight)
            },
            point = point.denormalizeToSurface(renderWidth, renderHeight)
        ) ?: return
        val committedStroke = _renderState.value.committedStrokes.firstOrNull { it.id == stroke.id }
            ?: return
        recordLocalAction(HistoryAction.EraseAction(stroke.id, committedStroke))
        persistenceStore.persistErase(stroke.id)
        _renderState.update {
            val next = it.copy(
                committedStrokes = it.committedStrokes.filterNot { committed ->
                    committed.id == stroke.id
                },
                remoteActiveStrokes = it.remoteActiveStrokes - stroke.id,
                cacheToken = it.cacheToken + 1
            )
            next.withUndoRedoAvailability()
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
        clearHistory()
        _renderState.update {
            val next = it.copy(
                committedStrokes = emptyList(),
                localActiveStroke = null,
                remoteActiveStrokes = emptyMap(),
                cacheToken = it.cacheToken + 1
            )
            next.withUndoRedoAvailability()
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
                    val next = it.copy(
                        committedStrokes = it.committedStrokes.filterNot { stroke ->
                            stroke.id == strokeId
                        },
                        remoteActiveStrokes = it.remoteActiveStrokes - strokeId,
                        revision = maxOf(it.revision, operation.serverRevision),
                        cacheToken = it.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
            }
            SyncOperationPayload.ClearCanvas -> {
                persistenceStore.persistClear(operation.serverRevision)
                clearHistory()
                _renderState.update {
                    val next = it.copy(
                        committedStrokes = emptyList(),
                        committedTextElements = emptyList(),
                        localActiveStroke = null,
                        remoteActiveStrokes = emptyMap(),
                        revision = maxOf(it.revision, operation.serverRevision),
                        cacheToken = it.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
            }
            is SyncOperationPayload.AddTextElement -> {
                val payload = operation.payload
                val element = payload.toDomainElement()
                persistenceStore.persistRemoteUpsertTextElement(element, operation.serverRevision)
                _renderState.update {
                    val nextElements = it.committedTextElements
                        .filterNot { existing -> existing.id == element.id } + element
                    it.copy(
                        committedTextElements = nextElements,
                        revision = maxOf(it.revision, operation.serverRevision),
                        cacheToken = it.cacheToken + 1
                    ).withUndoRedoAvailability()
                }
            }
            is SyncOperationPayload.UpdateTextElement -> {
                val payload = operation.payload
                val element = payload.toDomainElement()
                persistenceStore.persistRemoteUpsertTextElement(element, operation.serverRevision)
                _renderState.update {
                    val nextElements = it.committedTextElements
                        .filterNot { existing -> existing.id == element.id } + element
                    it.copy(
                        committedTextElements = nextElements,
                        revision = maxOf(it.revision, operation.serverRevision),
                        cacheToken = it.cacheToken + 1
                    ).withUndoRedoAvailability()
                }
            }
            SyncOperationPayload.DeleteTextElement -> {
                val elementId = operation.strokeId ?: return
                persistenceStore.persistRemoteDeleteTextElement(elementId, operation.serverRevision)
                _renderState.update {
                    val next = it.copy(
                        committedTextElements = it.committedTextElements.filterNot { element ->
                            element.id == elementId
                        },
                        revision = maxOf(it.revision, operation.serverRevision),
                        cacheToken = it.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
            }
        }
    }

    private suspend fun handleRecoverySnapshot(event: CanvasRuntimeEvent.RecoverySnapshot) {
        persistenceStore.replaceFromServerSnapshot(event.strokes, event.textElements, event.serverRevision)
        clearHistory()
        _renderState.update {
            val next = it.copy(
                committedStrokes = event.strokes,
                committedTextElements = event.textElements,
                localActiveStroke = null,
                remoteActiveStrokes = emptyMap(),
                revision = event.serverRevision,
                cacheToken = it.cacheToken + 1
            )
            next.withUndoRedoAvailability()
        }
    }

    private suspend fun handleRecoveryOperations(event: CanvasRuntimeEvent.RecoveryOperations) {
        if (!event.publishAtomically) {
            event.operations.forEach { applyRemoteOperation(it) }
            return
        }

        val initialState = _renderState.value
        var committedStrokes = initialState.committedStrokes
        var committedTextElements = initialState.committedTextElements
        var remoteActiveStrokes = initialState.remoteActiveStrokes
        var revision = initialState.revision
        var shouldClearLocalActive = false
        var cacheChanged = false

        event.operations.sortedBy { it.serverRevision }.forEach { operation ->
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
                    remoteActiveStrokes = remoteActiveStrokes + (stroke.id to stroke)
                }
                is SyncOperationPayload.AppendPoints -> {
                    val strokeId = operation.strokeId ?: return@forEach
                    val active = remoteActiveStrokes[strokeId] ?: return@forEach
                    remoteActiveStrokes = remoteActiveStrokes + (
                        strokeId to active.copy(points = active.points + operation.payload.points)
                    )
                }
                SyncOperationPayload.FinishStroke -> {
                    val strokeId = operation.strokeId ?: return@forEach
                    val finished = remoteActiveStrokes[strokeId]
                        ?: committedStrokes.firstOrNull { it.id == strokeId }
                        ?: return@forEach
                    persistenceStore.persistRemoteCommittedStroke(finished, operation.serverRevision)
                    committedStrokes = committedStrokes.filterNot { it.id == strokeId } + finished
                    remoteActiveStrokes = remoteActiveStrokes - strokeId
                    cacheChanged = true
                }
                SyncOperationPayload.DeleteStroke -> {
                    val strokeId = operation.strokeId ?: return@forEach
                    persistenceStore.persistErase(strokeId, operation.serverRevision)
                    committedStrokes = committedStrokes.filterNot { it.id == strokeId }
                    remoteActiveStrokes = remoteActiveStrokes - strokeId
                    cacheChanged = true
                }
                SyncOperationPayload.ClearCanvas -> {
                    persistenceStore.persistClear(operation.serverRevision)
                    clearHistory()
                    committedStrokes = emptyList()
                    committedTextElements = emptyList()
                    remoteActiveStrokes = emptyMap()
                    shouldClearLocalActive = true
                    cacheChanged = true
                }
                is SyncOperationPayload.AddTextElement -> {
                    val element = operation.payload.toDomainElement()
                    persistenceStore.persistRemoteUpsertTextElement(element, operation.serverRevision)
                    committedTextElements = committedTextElements.filterNot { it.id == element.id } + element
                    cacheChanged = true
                }
                is SyncOperationPayload.UpdateTextElement -> {
                    val element = operation.payload.toDomainElement()
                    persistenceStore.persistRemoteUpsertTextElement(element, operation.serverRevision)
                    committedTextElements = committedTextElements.filterNot { it.id == element.id } + element
                    cacheChanged = true
                }
                SyncOperationPayload.DeleteTextElement -> {
                    val elementId = operation.strokeId ?: return@forEach
                    persistenceStore.persistRemoteDeleteTextElement(elementId, operation.serverRevision)
                    committedTextElements = committedTextElements.filterNot { it.id == elementId }
                    cacheChanged = true
                }
            }
            revision = maxOf(revision, operation.serverRevision)
        }

        _renderState.update {
            val next = it.copy(
                committedStrokes = committedStrokes,
                committedTextElements = committedTextElements,
                localActiveStroke = if (shouldClearLocalActive) null else it.localActiveStroke,
                remoteActiveStrokes = remoteActiveStrokes,
                revision = revision,
                cacheToken = if (cacheChanged) it.cacheToken + 1 else it.cacheToken
            )
            next.withUndoRedoAvailability()
        }
    }

    private fun handleFlowControl(event: CanvasRuntimeEvent.FlowControl) {
        activeAppendHz = event.maxAppendHz.coerceIn(MIN_APPEND_HZ, DEFAULT_APPEND_HZ)
    }

    private fun CanvasRenderState.withUndoRedoAvailability(): CanvasRenderState = copy(
        canUndo = localActiveStroke != null || undoStack.isNotEmpty(),
        canRedo = localActiveStroke == null && redoStack.isNotEmpty()
    )

    private suspend fun handleUndo() {
        val activeStroke = _renderState.value.localActiveStroke
        if (activeStroke != null) {
            pendingAppendPoints.clear()
            _renderState.update { current ->
                val next = current.copy(localActiveStroke = null)
                next.withUndoRedoAvailability()
            }
            outbound.send(
                newClientOperation(
                    type = DrawingOperationType.DELETE_STROKE,
                    strokeId = activeStroke.id,
                    payload = SyncOperationPayload.DeleteStroke
                )
            )
            return
        }

        val action = if (undoStack.isEmpty()) null else undoStack.removeLast()
        if (action == null) {
            refreshUndoRedoAvailability()
            return
        }
        when (action) {
            is HistoryAction.DrawAction -> {
                redoStack.addLast(action)
                persistenceStore.persistErase(action.stroke.id)
                _renderState.update { current ->
                    val next = current.copy(
                        committedStrokes = current.committedStrokes.filterNot { it.id == action.stroke.id },
                        remoteActiveStrokes = current.remoteActiveStrokes - action.stroke.id,
                        cacheToken = current.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.DELETE_STROKE,
                        strokeId = action.stroke.id,
                        payload = SyncOperationPayload.DeleteStroke
                    )
                )
            }
            is HistoryAction.EraseAction -> {
                val restored = replayStroke(action.deletedStroke)
                redoStack.addLast(HistoryAction.EraseAction(deletedStrokeId = restored.id, deletedStroke = action.deletedStroke))
                rewriteDrawActionStrokeId(
                    oldStrokeId = action.deletedStrokeId,
                    newStrokeId = restored.id
                )
                refreshUndoRedoAvailability()
            }
            is HistoryAction.AddTextAction -> {
                redoStack.addLast(action)
                persistenceStore.persistDeleteTextElement(action.elementId)
                _renderState.update { current ->
                    val next = current.copy(
                        committedTextElements = current.committedTextElements.filterNot { it.id == action.elementId },
                        cacheToken = current.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.DELETE_TEXT_ELEMENT,
                        strokeId = action.elementId,
                        payload = SyncOperationPayload.DeleteTextElement
                    )
                )
            }
            is HistoryAction.DeleteTextAction -> {
                redoStack.addLast(action)
                persistenceStore.persistUpsertTextElement(action.element)
                _renderState.update { current ->
                    val nextElements = current.committedTextElements
                        .filterNot { it.id == action.element.id } + action.element
                    val next = current.copy(
                        committedTextElements = nextElements,
                        cacheToken = current.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.ADD_TEXT_ELEMENT,
                        strokeId = action.element.id,
                        payload = action.element.toAddPayload()
                    )
                )
            }
            is HistoryAction.UpdateTextAction -> {
                redoStack.addLast(action)
                persistenceStore.persistUpsertTextElement(action.before)
                _renderState.update { current ->
                    val nextElements = current.committedTextElements
                        .filterNot { it.id == action.before.id } + action.before
                    val next = current.copy(
                        committedTextElements = nextElements,
                        cacheToken = current.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.UPDATE_TEXT_ELEMENT,
                        strokeId = action.before.id,
                        payload = action.before.toUpdatePayload()
                    )
                )
            }
        }
    }

    private suspend fun handleRedo() {
        if (_renderState.value.localActiveStroke != null) {
            refreshUndoRedoAvailability()
            return
        }

        val action = if (redoStack.isEmpty()) null else redoStack.removeLast()
        if (action == null) {
            refreshUndoRedoAvailability()
            return
        }
        when (action) {
            is HistoryAction.DrawAction -> {
                val replayed = replayStroke(action.stroke)
                undoStack.addLast(HistoryAction.DrawAction(replayed))
                trimHistory()
                refreshUndoRedoAvailability()
            }
            is HistoryAction.EraseAction -> {
                undoStack.addLast(action)
                trimHistory()
                persistenceStore.persistErase(action.deletedStrokeId)
                _renderState.update { current ->
                    val next = current.copy(
                        committedStrokes = current.committedStrokes.filterNot { it.id == action.deletedStrokeId },
                        remoteActiveStrokes = current.remoteActiveStrokes - action.deletedStrokeId,
                        cacheToken = current.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.DELETE_STROKE,
                        strokeId = action.deletedStrokeId,
                        payload = SyncOperationPayload.DeleteStroke
                    )
                )
            }
            is HistoryAction.AddTextAction -> {
                undoStack.addLast(action)
                trimHistory()
                persistenceStore.persistUpsertTextElement(action.addedElement)
                _renderState.update { current ->
                    val nextElements = current.committedTextElements
                        .filterNot { it.id == action.addedElement.id } + action.addedElement
                    val next = current.copy(
                        committedTextElements = nextElements,
                        cacheToken = current.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.ADD_TEXT_ELEMENT,
                        strokeId = action.addedElement.id,
                        payload = action.addedElement.toAddPayload()
                    )
                )
            }
            is HistoryAction.DeleteTextAction -> {
                undoStack.addLast(action)
                trimHistory()
                persistenceStore.persistDeleteTextElement(action.element.id)
                _renderState.update { current ->
                    val next = current.copy(
                        committedTextElements = current.committedTextElements.filterNot { it.id == action.element.id },
                        cacheToken = current.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.DELETE_TEXT_ELEMENT,
                        strokeId = action.element.id,
                        payload = SyncOperationPayload.DeleteTextElement
                    )
                )
            }
            is HistoryAction.UpdateTextAction -> {
                undoStack.addLast(action)
                trimHistory()
                persistenceStore.persistUpsertTextElement(action.after)
                _renderState.update { current ->
                    val nextElements = current.committedTextElements
                        .filterNot { it.id == action.after.id } + action.after
                    val next = current.copy(
                        committedTextElements = nextElements,
                        cacheToken = current.cacheToken + 1
                    )
                    next.withUndoRedoAvailability()
                }
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.UPDATE_TEXT_ELEMENT,
                        strokeId = action.after.id,
                        payload = action.after.toUpdatePayload()
                    )
                )
            }
        }
    }

    private suspend fun handleAddTextElement(element: CanvasTextElement) {
        persistenceStore.persistUpsertTextElement(element)
        recordLocalAction(HistoryAction.AddTextAction(elementId = element.id, addedElement = element))
        _renderState.update { current ->
            val nextElements = current.committedTextElements.filterNot { it.id == element.id } + element
            val next = current.copy(
                committedTextElements = nextElements,
                cacheToken = current.cacheToken + 1
            )
            next.withUndoRedoAvailability()
        }
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.ADD_TEXT_ELEMENT,
                strokeId = element.id,
                payload = element.toAddPayload()
            )
        )
    }

    private suspend fun handleUpdateTextElement(element: CanvasTextElement) {
        val before = _renderState.value.committedTextElements.firstOrNull { it.id == element.id }
        persistenceStore.persistUpsertTextElement(element)
        if (before != null && before != element) {
            recordLocalAction(HistoryAction.UpdateTextAction(before = before, after = element))
        }
        _renderState.update { current ->
            val nextElements = current.committedTextElements.filterNot { it.id == element.id } + element
            val next = current.copy(
                committedTextElements = nextElements,
                cacheToken = current.cacheToken + 1
            )
            next.withUndoRedoAvailability()
        }
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.UPDATE_TEXT_ELEMENT,
                strokeId = element.id,
                payload = element.toUpdatePayload()
            )
        )
    }

    private suspend fun handleDeleteTextElement(elementId: String) {
        val existing = _renderState.value.committedTextElements.firstOrNull { it.id == elementId }
            ?: return
        persistenceStore.persistDeleteTextElement(elementId)
        recordLocalAction(HistoryAction.DeleteTextAction(existing))
        _renderState.update { current ->
            val next = current.copy(
                committedTextElements = current.committedTextElements.filterNot { it.id == elementId },
                cacheToken = current.cacheToken + 1
            )
            next.withUndoRedoAvailability()
        }
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.DELETE_TEXT_ELEMENT,
                strokeId = elementId,
                payload = SyncOperationPayload.DeleteTextElement
            )
        )
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

    private fun recordLocalAction(action: HistoryAction) {
        redoStack.clear()
        undoStack.addLast(action)
        trimHistory()
    }

    private fun trimHistory() {
        while (undoStack.size > MAX_ACTIONS) {
            if (undoStack.isNotEmpty()) {
                undoStack.removeFirst()
            }
        }
        while (redoStack.size > MAX_ACTIONS) {
            if (redoStack.isNotEmpty()) {
                redoStack.removeFirst()
            }
        }
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun refreshUndoRedoAvailability() {
        _renderState.update { current -> current.withUndoRedoAvailability() }
    }

    private fun rewriteDrawActionStrokeId(oldStrokeId: String, newStrokeId: String) {
        if (oldStrokeId == newStrokeId) return
        val rewritten = undoStack.map { action ->
            when (action) {
                is HistoryAction.DrawAction ->
                    if (action.stroke.id == oldStrokeId) {
                        HistoryAction.DrawAction(action.stroke.copy(id = newStrokeId))
                    } else {
                        action
                    }
                is HistoryAction.EraseAction,
                is HistoryAction.AddTextAction,
                is HistoryAction.DeleteTextAction,
                is HistoryAction.UpdateTextAction -> action
            }
        }
        undoStack.clear()
        rewritten.forEach { undoStack.addLast(it) }
    }

    private suspend fun replayStroke(source: Stroke): Stroke {
        val points = source.points
        if (points.isEmpty()) return source

        val newStroke = source.copy(id = UUID.randomUUID().toString())
        persistenceStore.persistLocalCommittedStroke(newStroke)
        _renderState.update { current ->
            val next = current.copy(
                committedStrokes = current.committedStrokes + newStroke,
                cacheToken = current.cacheToken + 1
            )
            next.withUndoRedoAvailability()
        }
        outbound.send(
            newClientOperation(
                type = DrawingOperationType.ADD_STROKE,
                strokeId = newStroke.id,
                payload = newStroke.toAddStrokePayload()
            )
        )

        val remaining = points.drop(1)
        if (remaining.isNotEmpty()) {
            replayAppendPoints(newStroke.id, remaining)
        }

        outbound.send(
            newClientOperation(
                type = DrawingOperationType.FINISH_STROKE,
                strokeId = newStroke.id,
                payload = SyncOperationPayload.FinishStroke
            )
        )
        return newStroke
    }

    private suspend fun replayAppendPoints(strokeId: String, points: List<StrokePoint>) {
        var chunk = mutableListOf<StrokePoint>()
        for (point in points) {
            chunk.add(point)
            val candidate = newClientOperation(
                type = DrawingOperationType.APPEND_POINTS,
                strokeId = strokeId,
                payload = SyncOperationPayload.AppendPoints(chunk)
            )
            val sizeBytes = candidate.toWireJson().encodeToByteArray().size
            if (sizeBytes > MAX_APPEND_OP_BYTES && chunk.size > 1) {
                val last = chunk.removeAt(chunk.size - 1)
                outbound.send(
                    newClientOperation(
                        type = DrawingOperationType.APPEND_POINTS,
                        strokeId = strokeId,
                        payload = SyncOperationPayload.AppendPoints(chunk)
                    )
                )
                chunk = mutableListOf(last)
            }
        }
        if (chunk.isNotEmpty()) {
            outbound.send(
                newClientOperation(
                    type = DrawingOperationType.APPEND_POINTS,
                    strokeId = strokeId,
                    payload = SyncOperationPayload.AppendPoints(chunk)
                )
            )
        }
    }

    private companion object {
        const val DEFAULT_APPEND_HZ = 20
        const val MIN_APPEND_HZ = 10
        const val DEFAULT_POINT_THRESHOLD_PX = 0.5f
        const val MAX_ACTIONS = 50
        const val MAX_APPEND_OP_BYTES = 16 * 1024
    }

    private fun hasCanvasViewport(): Boolean =
        canvasViewportWidthPx > 0 && canvasViewportHeightPx > 0

    private fun normalizeCurrentWidth(widthPx: Float): Float =
        normalizeStrokeWidth(widthPx, canvasViewportWidthPx, canvasViewportHeightPx)

    private fun currentPointThreshold(): Float = normalizeStrokeWidth(
        width = DEFAULT_POINT_THRESHOLD_PX,
        surfaceWidth = canvasViewportWidthPx,
        surfaceHeight = canvasViewportHeightPx
    )
}

private data class RuntimeEventEnvelope(
    val event: CanvasRuntimeEvent,
    val completion: CompletableDeferred<Unit>? = null
)

private sealed interface HistoryAction {
    data class DrawAction(val stroke: Stroke) : HistoryAction
    data class EraseAction(val deletedStrokeId: String, val deletedStroke: Stroke) : HistoryAction
    data class AddTextAction(val elementId: String, val addedElement: CanvasTextElement) : HistoryAction
    data class DeleteTextAction(val element: CanvasTextElement) : HistoryAction
    data class UpdateTextAction(val before: CanvasTextElement, val after: CanvasTextElement) : HistoryAction
}

private fun SyncOperationPayload.AddTextElement.toDomainElement(): CanvasTextElement = CanvasTextElement(
    id = id,
    text = text,
    createdAt = createdAt,
    center = center,
    rotationRad = rotationRad,
    scale = scale,
    boxWidth = boxWidth,
    colorArgb = colorArgb,
    backgroundPillEnabled = backgroundPillEnabled,
    font = font,
    alignment = alignment
)

private fun SyncOperationPayload.UpdateTextElement.toDomainElement(): CanvasTextElement = CanvasTextElement(
    id = id,
    text = text,
    createdAt = createdAt,
    center = center,
    rotationRad = rotationRad,
    scale = scale,
    boxWidth = boxWidth,
    colorArgb = colorArgb,
    backgroundPillEnabled = backgroundPillEnabled,
    font = font,
    alignment = alignment
)

private fun CanvasTextElement.toAddPayload(): SyncOperationPayload.AddTextElement = SyncOperationPayload.AddTextElement(
    id = id,
    text = text,
    createdAt = createdAt,
    center = center,
    rotationRad = rotationRad,
    scale = scale,
    boxWidth = boxWidth,
    colorArgb = colorArgb,
    backgroundPillEnabled = backgroundPillEnabled,
    font = font,
    alignment = alignment
)

private fun CanvasTextElement.toUpdatePayload(): SyncOperationPayload.UpdateTextElement = SyncOperationPayload.UpdateTextElement(
    id = id,
    text = text,
    createdAt = createdAt,
    center = center,
    rotationRad = rotationRad,
    scale = scale,
    boxWidth = boxWidth,
    colorArgb = colorArgb,
    backgroundPillEnabled = backgroundPillEnabled,
    font = font,
    alignment = alignment
)
