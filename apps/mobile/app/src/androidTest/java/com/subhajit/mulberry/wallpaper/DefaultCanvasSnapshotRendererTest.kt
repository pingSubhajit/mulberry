package com.subhajit.mulberry.wallpaper

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.subhajit.mulberry.core.config.AppConfigFactory
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.StrokeEntity
import com.subhajit.mulberry.drawing.data.local.StrokePointEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultCanvasSnapshotRendererTest {
    private lateinit var database: DrawingDatabase
    private lateinit var renderer: DefaultCanvasSnapshotRenderer

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, DrawingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        renderer = DefaultCanvasSnapshotRenderer(
            context = context,
            database = database,
            drawingDao = database.drawingDao(),
            canvasMetadataDao = database.canvasMetadataDao(),
            appConfig = AppConfigFactory.fromFields(
                environmentName = "dev",
                apiBaseUrl = "http://localhost"
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WallpaperFiles.snapshotFile(context).delete()
    }

    @Test
    fun renderCurrentSnapshotProducesFileAndUpdatesMetadata() = runBlocking {
        database.drawingDao().insertStroke(
            StrokeEntity(
                id = "stroke-1",
                colorArgb = 0xFF000000L,
                width = 12f,
                createdAt = 10L
            )
        )
        database.drawingDao().insertStrokePoints(
            listOf(
                StrokePointEntity(strokeId = "stroke-1", pointIndex = 0, x = 10f, y = 10f),
                StrokePointEntity(strokeId = "stroke-1", pointIndex = 1, x = 100f, y = 100f)
            )
        )
        database.canvasMetadataDao().upsertMetadata(
            CanvasMetadataEntity.default().copy(
                revision = 2L,
                isSnapshotDirty = true
            )
        )

        val result = renderer.renderCurrentSnapshot()
        val metadata = database.canvasMetadataDao().getMetadata()

        assertTrue(java.io.File(result.imagePath).exists())
        assertEquals(2L, metadata?.lastSnapshotRevision)
        assertEquals(false, metadata?.isSnapshotDirty)
        assertNotNull(metadata?.cachedImagePath)
    }
}
