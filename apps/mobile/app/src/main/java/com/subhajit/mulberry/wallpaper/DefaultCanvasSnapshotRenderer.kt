package com.subhajit.mulberry.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import androidx.core.graphics.createBitmap
import androidx.room.withTransaction
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.DrawingDao
import com.subhajit.mulberry.drawing.data.local.toDomain
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.render.committedStrokeBitmapRenderer
import com.subhajit.mulberry.drawing.render.transformed
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class DefaultCanvasSnapshotRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DrawingDatabase,
    private val drawingDao: DrawingDao,
    private val canvasMetadataDao: CanvasMetadataDao,
    private val appConfig: AppConfig
) : CanvasSnapshotRenderer {

    override suspend fun renderCurrentSnapshot(): SnapshotRenderResult = withContext(Dispatchers.IO) {
        val dimensions = resolveSnapshotDimensions()
        val snapshotFile = WallpaperFiles.snapshotFile(context)
        snapshotFile.parentFile?.mkdirs()
        var capturedMetadata = CanvasMetadataEntity.default()
        var strokes = emptyList<Stroke>()

        database.withTransaction {
            capturedMetadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
            strokes = drawingDao.getStrokeGraphs().map { it.toDomain() }
        }

        val bitmap = createBitmap(dimensions.x, dimensions.y)
        val canvas = Canvas(bitmap)
        drawStrokes(
            canvas = canvas,
            strokes = strokes,
            sourceWidth = capturedMetadata.canvasWidthPx,
            sourceHeight = capturedMetadata.canvasHeightPx,
            screenWidth = context.resources.displayMetrics.widthPixels,
            screenHeight = context.resources.displayMetrics.heightPixels
        )

        FileOutputStream(snapshotFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()

        val renderedAt = System.currentTimeMillis()
        database.withTransaction {
            val latestMetadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
            val snapshotMatchesCurrent = latestMetadata.revision == capturedMetadata.revision
            canvasMetadataDao.upsertMetadata(
                latestMetadata.copy(
                    lastModifiedAt = renderedAt,
                    isSnapshotDirty = !snapshotMatchesCurrent,
                    lastSnapshotRevision = capturedMetadata.revision,
                    cachedImagePath = snapshotFile.absolutePath
                )
            )
        }

        SnapshotRenderResult(
            revision = capturedMetadata.revision,
            imagePath = snapshotFile.absolutePath,
            renderedAt = renderedAt,
            width = dimensions.x,
            height = dimensions.y
        )
    }

    override suspend fun clearSnapshots() {
        withContext(Dispatchers.IO) {
            WallpaperFiles.snapshotFile(context).delete()
        }
    }

    private fun resolveSnapshotDimensions(): Point {
        val displayMetrics = context.resources.displayMetrics
        val profile = resolveDeviceRenderProfile(context)
        val renderSize = resolveWallpaperRenderSurfaceSize(
            displayWidth = displayMetrics.widthPixels,
            displayHeight = displayMetrics.heightPixels,
            desiredWidth = WallpaperManager.getInstance(context).desiredMinimumWidth,
            desiredHeight = WallpaperManager.getInstance(context).desiredMinimumHeight,
            profile = profile
        )

        return Point(renderSize.width, renderSize.height)
    }

    private fun drawStrokes(
        canvas: Canvas,
        strokes: List<Stroke>,
        sourceWidth: Int,
        sourceHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val placement = calculatePlacement(
            bitmapWidth = canvas.width,
            bitmapHeight = canvas.height,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        appConfig.canvasStrokeRenderMode.committedStrokeBitmapRenderer().drawStrokes(
            canvas = canvas,
            strokes = strokes.map { stroke ->
                stroke.transformed(
                    scale = placement.scale,
                    offsetX = placement.offsetX,
                    offsetY = placement.offsetY
                )
            }
        )
    }

    internal fun calculatePlacement(
        bitmapWidth: Int,
        bitmapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): SnapshotPlacement = calculateSnapshotPlacement(
        bitmapWidth = bitmapWidth,
        bitmapHeight = bitmapHeight,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        screenWidth = screenWidth,
        screenHeight = screenHeight
    )
}

internal data class SnapshotPlacement(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val viewport: SnapshotViewport
)

internal data class SnapshotViewport(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

internal fun calculateSnapshotPlacement(
    bitmapWidth: Int,
    bitmapHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    screenWidth: Int,
    screenHeight: Int
): SnapshotPlacement {
    val safeBitmapWidth = bitmapWidth.coerceAtLeast(1)
    val safeBitmapHeight = bitmapHeight.coerceAtLeast(1)

    val viewportWidth = screenWidth
        .takeIf { it > 0 }
        ?.coerceAtMost(safeBitmapWidth)
        ?: safeBitmapWidth
    val viewportHeight = screenHeight
        .takeIf { it > 0 }
        ?.coerceAtMost(safeBitmapHeight)
        ?: safeBitmapHeight

    val viewportLeft = (safeBitmapWidth - viewportWidth) / 2f
    val viewportTop = (safeBitmapHeight - viewportHeight) / 2f
    val safeSourceWidth = sourceWidth.takeIf { it > 0 } ?: viewportWidth
    val safeSourceHeight = sourceHeight.takeIf { it > 0 } ?: viewportHeight
    val scale = minOf(
        viewportWidth.toFloat() / safeSourceWidth.toFloat(),
        viewportHeight.toFloat() / safeSourceHeight.toFloat()
    )

    val contentWidth = safeSourceWidth * scale
    val contentHeight = safeSourceHeight * scale
    val offsetX = viewportLeft + ((viewportWidth - contentWidth) / 2f)
    val offsetY = viewportTop + ((viewportHeight - contentHeight) / 2f)

    return SnapshotPlacement(
        scale = scale,
        offsetX = offsetX,
        offsetY = offsetY,
        viewport = SnapshotViewport(
            left = viewportLeft,
            top = viewportTop,
            right = viewportLeft + viewportWidth,
            bottom = viewportTop + viewportHeight
        )
    )
}
