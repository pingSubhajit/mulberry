package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.drawing.DrawingRepository
import com.subhajit.mulberry.drawing.engine.StrokeBuilder
import com.subhajit.mulberry.drawing.engine.StrokeHitTester
import com.subhajit.mulberry.drawing.geometry.normalizeStrokeWidth
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.ToolState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    override val toolState: Flow<ToolState> = MutableStateFlow(initialToolState)

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
