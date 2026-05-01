package com.subhajit.mulberry.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.content.res.ResourcesCompat
import androidx.room.withTransaction
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.data.local.CanvasTextElementDao
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.DrawingDao
import com.subhajit.mulberry.drawing.data.local.toDomain
import com.subhajit.mulberry.drawing.geometry.denormalizeToSurface
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.render.committedStrokeBitmapRenderer
import com.subhajit.mulberry.R
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
    private val canvasTextElementDao: CanvasTextElementDao,
    private val canvasMetadataDao: CanvasMetadataDao,
    private val appConfig: AppConfig
) : CanvasSnapshotRenderer {

    override suspend fun renderCurrentSnapshot(): SnapshotRenderResult = withContext(Dispatchers.IO) {
        val dimensions = resolveSnapshotDimensions()
        val snapshotFile = WallpaperFiles.snapshotFile(context)
        snapshotFile.parentFile?.mkdirs()
        var capturedMetadata = CanvasMetadataEntity.default()
        var strokes = emptyList<Stroke>()
        var textElements = emptyList<CanvasTextElement>()

        database.withTransaction {
            capturedMetadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
            strokes = drawingDao.getStrokeGraphs().map { it.toDomain() }
            textElements = canvasTextElementDao.getElements().map { it.toDomain() }
        }

        val bitmap = createBitmap(dimensions.x, dimensions.y)
        val canvas = Canvas(bitmap)
        val placement = calculatePlacement(
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            screenWidth = context.resources.displayMetrics.widthPixels,
            screenHeight = context.resources.displayMetrics.heightPixels,
            authoredViewportWidth = capturedMetadata.canvasWidthPx,
            authoredViewportHeight = capturedMetadata.canvasHeightPx
        )
        drawStrokes(
            canvas = canvas,
            strokes = strokes,
            placement = placement
        )
        drawTextElements(
            canvas = canvas,
            elements = textElements,
            placement = placement
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
        placement: SnapshotPlacement
    ) {
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

    private fun drawTextElements(
        canvas: Canvas,
        elements: List<CanvasTextElement>,
        placement: SnapshotPlacement
    ) {
        if (elements.isEmpty()) return

        val density = context.resources.displayMetrics.density
        val baseTextSizePx = 34f * density
        val pillPaddingPx = 12f * density
        val pillCornerPx = 18f * density

        val poppins = loadTypeface(R.font.poppins_regular) ?: Typeface.DEFAULT
        val virgil = loadTypeface(R.font.virgil_regular) ?: Typeface.DEFAULT

        for (element in elements) {
            val center = element.center.denormalizeToSurface(
                width = placement.viewport.width,
                height = placement.viewport.height,
                offsetX = placement.offsetX,
                offsetY = placement.offsetY
            )
            val wrapWidth = (element.boxWidth * placement.viewport.width).toInt().coerceAtLeast(1)
            val typeface = when (element.font) {
                CanvasTextFont.POPPINS -> poppins
                CanvasTextFont.VIRGIL -> virgil
            }
            val backgroundColor = element.colorArgb.toInt()
            val textColor = if (element.backgroundPillEnabled) {
                if (luminance(backgroundColor) > 0.55f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            } else {
                backgroundColor
            }

            val paint = TextPaint().apply {
                isAntiAlias = true
                color = textColor
                textSize = baseTextSizePx
                this.typeface = typeface
            }
            val alignment = when (element.alignment) {
                CanvasTextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                CanvasTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                CanvasTextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            }
            val layout = StaticLayout.Builder.obtain(element.text, 0, element.text.length, paint, wrapWidth)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()

            canvas.save()
            canvas.translate(center.x, center.y)
            canvas.rotate((element.rotationRad * 180f / Math.PI).toFloat())
            canvas.scale(element.scale, element.scale)

            val left = -layout.width / 2f
            val top = -layout.height / 2f
            if (element.backgroundPillEnabled) {
                val pillPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = backgroundColor
                }
                val rect = RectF(
                    left - pillPaddingPx,
                    top - pillPaddingPx,
                    left + layout.width + pillPaddingPx,
                    top + layout.height + pillPaddingPx
                )
                canvas.drawRoundRect(rect, pillCornerPx, pillCornerPx, pillPaint)
            }
            canvas.translate(left, top)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun loadTypeface(fontResId: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(context, fontResId) }.getOrNull()

    private fun luminance(color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
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
