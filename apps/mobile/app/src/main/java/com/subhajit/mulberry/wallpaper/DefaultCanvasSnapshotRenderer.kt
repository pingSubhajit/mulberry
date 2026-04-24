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
import com.subhajit.mulberry.drawing.geometry.denormalizeToSurface
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.render.committedStrokeBitmapRenderer
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
            screenWidth = context.resources.displayMetrics.widthPixels,
            screenHeight = context.resources.displayMetrics.heightPixels,
            authoredViewportWidth = capturedMetadata.canvasWidthPx,
            authoredViewportHeight = capturedMetadata.canvasHeightPx
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
        screenWidth: Int,
        screenHeight: Int,
        authoredViewportWidth: Int,
        authoredViewportHeight: Int
    ) {
        val placement = calculatePlacement(
            bitmapWidth = canvas.width,
            bitmapHeight = canvas.height,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            authoredViewportWidth = authoredViewportWidth,
            authoredViewportHeight = authoredViewportHeight
        )

        appConfig.canvasStrokeRenderMode.committedStrokeBitmapRenderer().drawStrokes(
            canvas = canvas,
            strokes = strokes.map { stroke ->
                stroke.denormalizeToSurface(
                    width = placement.viewport.width,
                    height = placement.viewport.height,
                    offsetX = placement.offsetX,
                    offsetY = placement.offsetY
                )
            }
        )
    }

    internal fun calculatePlacement(
        bitmapWidth: Int,
        bitmapHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        authoredViewportWidth: Int,
        authoredViewportHeight: Int
    ): SnapshotPlacement = calculateSnapshotPlacement(
        bitmapWidth = bitmapWidth,
        bitmapHeight = bitmapHeight,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        authoredViewportWidth = authoredViewportWidth,
        authoredViewportHeight = authoredViewportHeight
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
) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

internal fun calculateSnapshotPlacement(
    bitmapWidth: Int,
    bitmapHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
    authoredViewportWidth: Int,
    authoredViewportHeight: Int
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
    val authoredAspectRatio = authoredViewportWidth.takeIf { it > 0 }
        ?.let { safeAuthoredWidth ->
            authoredViewportHeight.takeIf { it > 0 }?.let { safeAuthoredHeight ->
                safeAuthoredWidth.toFloat() / safeAuthoredHeight.toFloat()
            }
        }
        ?: (viewportWidth.toFloat() / viewportHeight.toFloat())

    val screenAspectRatio = viewportWidth.toFloat() / viewportHeight.toFloat()
    val contentWidth: Float
    val contentHeight: Float

    if (screenAspectRatio > authoredAspectRatio) {
        contentHeight = viewportHeight.toFloat()
        contentWidth = contentHeight * authoredAspectRatio
    } else {
        contentWidth = viewportWidth.toFloat()
        contentHeight = contentWidth / authoredAspectRatio
    }

    val offsetX = viewportLeft + ((viewportWidth - contentWidth) / 2f)
    val offsetY = viewportTop + ((viewportHeight - contentHeight) / 2f)

    return SnapshotPlacement(
        scale = minOf(contentWidth, contentHeight),
        offsetX = offsetX,
        offsetY = offsetY,
        viewport = SnapshotViewport(
            left = offsetX,
            top = offsetY,
            right = offsetX + contentWidth,
            bottom = offsetY + contentHeight
        )
    )
}
