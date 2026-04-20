package com.subhajit.elaris.drawing.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subhajit.elaris.drawing.data.local.CanvasMetadataEntity
import com.subhajit.elaris.drawing.data.local.DrawingDatabase
import com.subhajit.elaris.drawing.data.local.DrawingOperationEntity
import com.subhajit.elaris.drawing.data.local.StrokeEntity
import com.subhajit.elaris.drawing.data.local.StrokePointEntity
import com.subhajit.elaris.drawing.model.DrawingOperationType
import com.subhajit.elaris.drawing.model.DrawingTool
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawingDatabaseTest {
    private lateinit var database: DrawingDatabase

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, DrawingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistAndReloadCommittedStrokesWithOrderedPoints() = runBlocking {
        database.drawingDao().insertStroke(
            StrokeEntity(
                id = "stroke-1",
                colorArgb = 0xFFFF6F2CL,
                width = 12f,
                createdAt = 10L
            )
        )
        database.drawingDao().insertStrokePoints(
            listOf(
                StrokePointEntity(strokeId = "stroke-1", pointIndex = 1, x = 20f, y = 20f),
                StrokePointEntity(strokeId = "stroke-1", pointIndex = 0, x = 10f, y = 10f)
            )
        )

        val strokes = database.drawingDao().getStrokeGraphs()

        assertEquals(1, strokes.size)
        assertEquals(2, strokes.first().points.size)
        assertEquals(0, strokes.first().points.first().pointIndex)
    }

    @Test
    fun clearOperationLeavesNoMaterializedStrokes() = runBlocking {
        database.drawingDao().insertStroke(
            StrokeEntity(
                id = "stroke-1",
                colorArgb = 0xFF000000L,
                width = 8f,
                createdAt = 10L
            )
        )
        database.drawingDao().insertStrokePoints(
            listOf(StrokePointEntity(strokeId = "stroke-1", pointIndex = 0, x = 1f, y = 1f))
        )
        database.drawingDao().clearStrokes()
        database.drawingOperationsDao().insertOperation(
            DrawingOperationEntity(
                type = DrawingOperationType.CLEAR_CANVAS,
                revision = 1L,
                createdAt = 20L
            )
        )

        assertTrue(database.drawingDao().getStrokeGraphs().isEmpty())
        assertEquals(
            DrawingOperationType.CLEAR_CANVAS,
            database.drawingOperationsDao().getOperations().last().type
        )
    }

    @Test
    fun metadataUpdatesPersistSelectedToolAndSnapshotState() = runBlocking {
        database.canvasMetadataDao().upsertMetadata(
            CanvasMetadataEntity.default().copy(
                revision = 2L,
                selectedTool = DrawingTool.ERASE,
                selectedWidth = 14f,
                isSnapshotDirty = true
            )
        )

        val metadata = database.canvasMetadataDao().getMetadata()

        assertEquals(2L, metadata?.revision)
        assertEquals(DrawingTool.ERASE, metadata?.selectedTool)
        assertEquals(14f, metadata?.selectedWidth ?: 0f, 0.001f)
        assertTrue(metadata?.isSnapshotDirty == true)
    }
}
