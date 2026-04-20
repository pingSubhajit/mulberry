package com.subhajit.elaris.drawing.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subhajit.elaris.drawing.data.local.DrawingDatabase
import com.subhajit.elaris.drawing.engine.StrokeBuilder
import com.subhajit.elaris.drawing.model.DrawingTool
import com.subhajit.elaris.drawing.model.StrokePoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDrawingRepositoryTest {
    private lateinit var database: DrawingDatabase
    private lateinit var repository: RoomDrawingRepository

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, DrawingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = createRepository()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun firstLaunchReturnsEmptyCanvasWithDefaultToolState() = runBlocking {
        val canvasState = repository.canvasState.first()
        val toolState = repository.toolState.first()

        assertTrue(canvasState.isEmpty)
        assertEquals(DrawingTool.DRAW, toolState.activeTool)
    }

    @Test
    fun committedStrokesSurviveRepositoryRecreation() = runBlocking {
        repository.startStroke(StrokePoint(10f, 10f))
        repository.appendPoint(StrokePoint(20f, 20f))
        repository.finishStroke()

        val recreated = createRepository()
        val canvasState = recreated.canvasState.first { it.strokes.isNotEmpty() }

        assertEquals(1, canvasState.strokes.size)
        assertEquals(2, canvasState.strokes.first().points.size)
    }

    @Test
    fun colorAndWidthChangesAffectFutureStrokesOnly() = runBlocking {
        repository.setBrushColor(0xFF000000L)
        repository.setBrushWidth(6f)
        repository.startStroke(StrokePoint(0f, 0f))
        repository.appendPoint(StrokePoint(20f, 0f))
        repository.finishStroke()

        repository.setBrushColor(0xFF0E7C59L)
        repository.setBrushWidth(18f)
        repository.startStroke(StrokePoint(0f, 20f))
        repository.appendPoint(StrokePoint(20f, 20f))
        repository.finishStroke()

        val strokes = repository.canvasState.first { it.strokes.size == 2 }.strokes

        assertEquals(0xFF000000L, strokes[0].colorArgb)
        assertEquals(6f, strokes[0].width, 0.001f)
        assertEquals(0xFF0E7C59L, strokes[1].colorArgb)
        assertEquals(18f, strokes[1].width, 0.001f)
    }

    @Test
    fun canvasViewportPersistsAcrossRepositoryRecreation() = runBlocking {
        repository.setCanvasViewport(widthPx = 320, heightPx = 640)

        val recreated = createRepository()
        val metadata = recreated.canvasState.first().snapshotState
        val storedMetadata = database.canvasMetadataDao().getMetadata()

        assertEquals(320, storedMetadata?.canvasWidthPx)
        assertEquals(640, storedMetadata?.canvasHeightPx)
        assertTrue(metadata.isDirty)
    }

    @Test
    fun erasingStrokePersistsAcrossReload() = runBlocking {
        repository.startStroke(StrokePoint(0f, 0f))
        repository.appendPoint(StrokePoint(10f, 10f))
        repository.finishStroke()

        val strokeId = repository.canvasState.first { it.strokes.isNotEmpty() }.strokes.first().id
        repository.eraseStroke(strokeId)

        val recreated = createRepository()
        val canvasState = recreated.canvasState.first()

        assertTrue(canvasState.strokes.isEmpty())
    }

    @Test
    fun clearPersistsAcrossReloadAndMarksCanvasEmpty() = runBlocking {
        repository.startStroke(StrokePoint(0f, 0f))
        repository.appendPoint(StrokePoint(10f, 10f))
        repository.finishStroke()

        repository.clearCanvas()

        val recreated = createRepository()
        val canvasState = recreated.canvasState.first()

        assertTrue(canvasState.isEmpty)
        assertTrue(canvasState.strokes.isEmpty())
    }

    @Test
    fun committedChangesMarkSnapshotDirty() = runBlocking {
        repository.startStroke(StrokePoint(0f, 0f))
        repository.appendPoint(StrokePoint(10f, 10f))
        repository.finishStroke()

        val canvasState = repository.canvasState.first { it.strokes.isNotEmpty() }

        assertTrue(canvasState.snapshotState.isDirty)
        assertEquals(canvasState.revision, canvasState.snapshotState.lastSnapshotRevision + 1)
    }

    @Test
    fun resetClearsAllDrawingState() = runBlocking {
        repository.startStroke(StrokePoint(0f, 0f))
        repository.appendPoint(StrokePoint(10f, 10f))
        repository.finishStroke()
        repository.setTool(DrawingTool.ERASE)

        repository.resetAllDrawingState()

        val canvasState = repository.canvasState.first()
        val toolState = repository.toolState.first()

        assertTrue(canvasState.strokes.isEmpty())
        assertTrue(canvasState.isEmpty)
        assertEquals(DrawingTool.DRAW, toolState.activeTool)
        assertTrue(canvasState.snapshotState.isDirty)
        assertEquals(0L, canvasState.snapshotState.lastSnapshotRevision)
        assertEquals(null, canvasState.snapshotState.cachedImagePath)
        assertFalse(database.drawingOperationsDao().getOperations().isNotEmpty())
    }

    private fun createRepository(): RoomDrawingRepository = RoomDrawingRepository(
        database = database,
        drawingDao = database.drawingDao(),
        drawingOperationsDao = database.drawingOperationsDao(),
        canvasMetadataDao = database.canvasMetadataDao(),
        strokeBuilder = StrokeBuilder()
    )
}
