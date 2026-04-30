package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.engine.StrokeBuilder
import com.subhajit.mulberry.drawing.engine.StrokeHitTester
import com.subhajit.mulberry.drawing.geometry.normalizeStrokeWidth
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.ToolState
import com.subhajit.mulberry.sync.CanvasSyncOperation
import com.subhajit.mulberry.sync.toWireJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCanvasRuntimeTest {

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `local press is ignored until canvas viewport is known`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()

        runtime.submitAndAwait(
            CanvasRuntimeEvent.LocalPress(
                StrokePoint(x = 0.5f, y = 0.5f)
            )
        )

        assertNull(runtime.renderState.value.localActiveStroke)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `local press uses normalized width after viewport is set`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()

        runtime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx = 200, heightPx = 400))
        runtime.submitAndAwait(
            CanvasRuntimeEvent.LocalPress(
                StrokePoint(x = 0.5f, y = 0.5f)
            )
        )

        val activeStroke = runtime.renderState.value.localActiveStroke
        requireNotNull(activeStroke)
        assertEquals(
            normalizeStrokeWidth(DrawingDefaults.DEFAULT_WIDTH, 200, 400),
            activeStroke.width,
            0.0001f
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `local press still works after stop and restart without a fresh viewport event`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()

        runtime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx = 200, heightPx = 400))
        runtime.stop()
        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()

        runtime.submitAndAwait(
            CanvasRuntimeEvent.LocalPress(
                StrokePoint(x = 0.5f, y = 0.5f)
            )
        )

        val activeStroke = runtime.renderState.value.localActiveStroke
        requireNotNull(activeStroke)
        assertEquals(
            normalizeStrokeWidth(DrawingDefaults.DEFAULT_WIDTH, 200, 400),
            activeStroke.width,
            0.0001f
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `draw undo removes stroke and enables redo`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        val outbound = mutableListOf<CanvasSyncOperation>()
        val job = backgroundScope.launch {
            runtime.outboundOperations.collect { outbound += it }
        }

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()

        runtime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx = 200, heightPx = 400))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalPress(StrokePoint(x = 0.2f, y = 0.2f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalDrag(StrokePoint(x = 0.25f, y = 0.25f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalRelease)
        advanceUntilIdle()

        val strokeId = runtime.renderState.value.committedStrokes.single().id
        assertTrue(runtime.renderState.value.canUndo)
        assertFalse(runtime.renderState.value.canRedo)

        runtime.submitAndAwait(CanvasRuntimeEvent.Undo)
        advanceUntilIdle()

        assertTrue(runtime.renderState.value.committedStrokes.isEmpty())
        assertFalse(runtime.renderState.value.canUndo)
        assertTrue(runtime.renderState.value.canRedo)

        val delete = outbound.lastOrNull { it.type == DrawingOperationType.DELETE_STROKE }
        requireNotNull(delete)
        assertEquals(strokeId, delete.strokeId)

        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `draw undo redo replays as new stroke id`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        val outbound = mutableListOf<CanvasSyncOperation>()
        val job = backgroundScope.launch {
            runtime.outboundOperations.collect { outbound += it }
        }

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()

        runtime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx = 200, heightPx = 400))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalPress(StrokePoint(x = 0.2f, y = 0.2f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalDrag(StrokePoint(x = 0.25f, y = 0.25f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalRelease)
        advanceUntilIdle()

        val originalId = runtime.renderState.value.committedStrokes.single().id
        runtime.submitAndAwait(CanvasRuntimeEvent.Undo)
        advanceUntilIdle()

        outbound.clear()

        runtime.submitAndAwait(CanvasRuntimeEvent.Redo)
        advanceUntilIdle()

        val replayed = runtime.renderState.value.committedStrokes.single()
        assertNotEquals(originalId, replayed.id)
        assertTrue(runtime.renderState.value.canUndo)
        assertFalse(runtime.renderState.value.canRedo)

        val add = outbound.firstOrNull { it.type == DrawingOperationType.ADD_STROKE }
        val finish = outbound.lastOrNull { it.type == DrawingOperationType.FINISH_STROKE }
        requireNotNull(add)
        requireNotNull(finish)
        assertEquals(replayed.id, add.strokeId)
        assertEquals(replayed.id, finish.strokeId)

        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `erase undo restores stroke and redo erases restored id`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        val outbound = mutableListOf<CanvasSyncOperation>()
        val job = backgroundScope.launch {
            runtime.outboundOperations.collect { outbound += it }
        }

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()
        runtime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx = 200, heightPx = 400))

        // Draw one stroke.
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalPress(StrokePoint(x = 0.5f, y = 0.5f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalDrag(StrokePoint(x = 0.52f, y = 0.52f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalRelease)
        advanceUntilIdle()
        val drawnId = runtime.renderState.value.committedStrokes.single().id

        // Switch to eraser and erase it.
        drawingRepository.setToolForTest(DrawingTool.ERASE)
        advanceUntilIdle()
        runtime.submitAndAwait(CanvasRuntimeEvent.EraseAt(StrokePoint(x = 0.5f, y = 0.5f)))
        advanceUntilIdle()

        assertTrue(runtime.renderState.value.committedStrokes.isEmpty())
        assertTrue(runtime.renderState.value.canUndo)
        assertFalse(runtime.renderState.value.canRedo)

        outbound.clear()

        runtime.submitAndAwait(CanvasRuntimeEvent.Undo)
        advanceUntilIdle()

        val restoredId = runtime.renderState.value.committedStrokes.single().id
        assertNotEquals(drawnId, restoredId)
        assertTrue(runtime.renderState.value.canRedo)

        // Redo should delete the restored id.
        outbound.clear()
        runtime.submitAndAwait(CanvasRuntimeEvent.Redo)
        advanceUntilIdle()

        assertTrue(runtime.renderState.value.committedStrokes.isEmpty())
        val redoDelete = outbound.lastOrNull { it.type == DrawingOperationType.DELETE_STROKE }
        requireNotNull(redoDelete)
        assertEquals(restoredId, redoDelete.strokeId)

        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `draw erase undo-erase undo rewrites draw action to restored id`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()
        runtime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx = 200, heightPx = 400))

        // Draw a stroke.
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalPress(StrokePoint(x = 0.4f, y = 0.4f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalDrag(StrokePoint(x = 0.45f, y = 0.45f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalRelease)
        advanceUntilIdle()

        val originalId = runtime.renderState.value.committedStrokes.single().id

        // Erase it.
        drawingRepository.setToolForTest(DrawingTool.ERASE)
        advanceUntilIdle()
        runtime.submitAndAwait(CanvasRuntimeEvent.EraseAt(StrokePoint(x = 0.4f, y = 0.4f)))
        advanceUntilIdle()
        assertTrue(runtime.renderState.value.committedStrokes.isEmpty())

        // Undo erase restores with new id.
        runtime.submitAndAwait(CanvasRuntimeEvent.Undo)
        advanceUntilIdle()
        val restoredId = runtime.renderState.value.committedStrokes.single().id
        assertNotEquals(originalId, restoredId)

        // Undo again should delete the restored incarnation.
        runtime.submitAndAwait(CanvasRuntimeEvent.Undo)
        advanceUntilIdle()
        assertTrue(runtime.renderState.value.committedStrokes.isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `undo during an in-progress stroke cancels it`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        val outbound = mutableListOf<CanvasSyncOperation>()
        val job = backgroundScope.launch {
            runtime.outboundOperations.collect { outbound += it }
        }

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()
        runtime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx = 200, heightPx = 400))

        runtime.submitAndAwait(CanvasRuntimeEvent.LocalPress(StrokePoint(x = 0.2f, y = 0.2f)))
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalDrag(StrokePoint(x = 0.25f, y = 0.25f)))
        advanceUntilIdle()

        val activeId = runtime.renderState.value.localActiveStroke?.id
        requireNotNull(activeId)

        outbound.clear()
        runtime.submitAndAwait(CanvasRuntimeEvent.Undo)
        advanceUntilIdle()

        assertNull(runtime.renderState.value.localActiveStroke)
        val delete = outbound.lastOrNull { it.type == DrawingOperationType.DELETE_STROKE }
        requireNotNull(delete)
        assertEquals(activeId, delete.strokeId)

        job.cancel()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `redo replay chunks large append operations`() = runTest {
        val drawingRepository = FakeDrawingRepository()
        val persistenceStore = FakeCanvasPersistenceStore()
        val runtime = DefaultCanvasRuntime(
            persistenceStore = persistenceStore,
            drawingRepository = drawingRepository,
            strokeBuilder = StrokeBuilder(),
            strokeHitTester = StrokeHitTester(),
            applicationScope = backgroundScope
        )

        val outbound = mutableListOf<CanvasSyncOperation>()
        val job = backgroundScope.launch {
            runtime.outboundOperations.collect { outbound += it }
        }

        runtime.start(pairSessionId = "pair", userId = "user")
        advanceUntilIdle()
        runtime.submitAndAwait(CanvasRuntimeEvent.CanvasViewportChanged(widthPx = 200, heightPx = 400))

        runtime.submitAndAwait(CanvasRuntimeEvent.LocalPress(StrokePoint(x = 0.1f, y = 0.1f)))
        // Add a lot of points so replay must chunk.
        for (i in 1..2_000) {
            val x = 0.1f + (i % 1000) * 0.0008f
            val y = 0.1f + (i % 100) * 0.001f
            runtime.submit(CanvasRuntimeEvent.LocalDrag(StrokePoint(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))))
        }
        runtime.submitAndAwait(CanvasRuntimeEvent.LocalRelease)
        advanceUntilIdle()

        runtime.submitAndAwait(CanvasRuntimeEvent.Undo)
        advanceUntilIdle()

        outbound.clear()
        runtime.submitAndAwait(CanvasRuntimeEvent.Redo)
        advanceUntilIdle()

        val replayStrokeId = outbound.firstOrNull { it.type == DrawingOperationType.ADD_STROKE }?.strokeId
        requireNotNull(replayStrokeId)
        val appendOps = outbound.filter { it.type == DrawingOperationType.APPEND_POINTS && it.strokeId == replayStrokeId }
        assertTrue(appendOps.isNotEmpty())
        appendOps.forEach { op ->
            val bytes = op.toWireJson().encodeToByteArray().size
            assertTrue("append op too large bytes=$bytes", bytes <= 16 * 1024)
        }

        job.cancel()
    }
}

private class FakeCanvasPersistenceStore(
    private val toolState: ToolState = ToolState(
        activeTool = DrawingTool.DRAW,
        selectedColorArgb = DrawingDefaults.DEFAULT_COLOR_ARGB,
        selectedWidth = DrawingDefaults.DEFAULT_WIDTH
    )
) : CanvasPersistenceStore {
    override suspend fun loadCanvasState(): CanvasState = CanvasState()

    override suspend fun loadToolState(): ToolState = toolState

    override suspend fun persistLocalCommittedStroke(stroke: Stroke): Long = 1L

    override suspend fun persistRemoteCommittedStroke(stroke: Stroke, serverRevision: Long) = Unit

    override suspend fun persistErase(strokeId: String, serverRevision: Long?) = Unit

    override suspend fun persistClear(serverRevision: Long?) = Unit

    override suspend fun replaceFromServerSnapshot(strokes: List<Stroke>, serverRevision: Long) = Unit
}

private class FakeDrawingRepository(
    initialToolState: ToolState = ToolState(
        activeTool = DrawingTool.DRAW,
        selectedColorArgb = DrawingDefaults.DEFAULT_COLOR_ARGB,
        selectedWidth = DrawingDefaults.DEFAULT_WIDTH
    )
) : DrawingRepository {
    override val canvasState: Flow<CanvasState> = MutableStateFlow(CanvasState())
    private val toolStateFlow = MutableStateFlow(initialToolState)
    override val toolState: Flow<ToolState> = toolStateFlow

    fun setToolForTest(tool: DrawingTool) {
        toolStateFlow.update { it.copy(activeTool = tool) }
    }

    override suspend fun startStroke(point: StrokePoint): Stroke? = null

    override suspend fun appendPoint(point: StrokePoint): Stroke? = null

    override suspend fun finishStroke(): Stroke? = null

    override suspend fun setBrushColor(colorArgb: Long) = Unit

    override suspend fun setBrushWidth(width: Float) = Unit

    override suspend fun setTool(tool: DrawingTool) = Unit

    override suspend fun setCanvasViewport(widthPx: Int, heightPx: Int) = Unit

    override suspend fun eraseStroke(strokeId: String) = Unit

    override suspend fun clearCanvas() = Unit

    override suspend fun applyRemoteAddStroke(stroke: Stroke, serverRevision: Long) = Unit

    override suspend fun applyRemoteAppendPoints(
        strokeId: String,
        points: List<StrokePoint>,
        serverRevision: Long
    ) = Unit

    override suspend fun applyRemoteFinishStroke(strokeId: String, serverRevision: Long) = Unit

    override suspend fun applyRemoteDeleteStroke(strokeId: String, serverRevision: Long) = Unit

    override suspend fun applyRemoteClearCanvas(serverRevision: Long) = Unit

    override suspend fun replaceWithRemoteSnapshot(strokes: List<Stroke>, serverRevision: Long) = Unit

    override suspend fun persistLocalCommittedStroke(stroke: Stroke): Long = 1L

    override suspend fun persistRemoteCommittedStroke(stroke: Stroke, serverRevision: Long) = Unit

    override suspend fun resetAllDrawingState() = Unit
}
