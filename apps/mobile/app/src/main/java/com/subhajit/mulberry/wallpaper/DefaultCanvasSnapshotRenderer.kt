package com.subhajit.mulberry.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.room.withTransaction
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import com.subhajit.mulberry.drawing.data.local.CanvasPhotoElementDao
import com.subhajit.mulberry.drawing.data.local.CanvasPhotoElementEntity
import com.subhajit.mulberry.drawing.data.local.CanvasStickerElementDao
import com.subhajit.mulberry.drawing.data.local.CanvasTextElementDao
import com.subhajit.mulberry.drawing.data.local.CanvasStickerElementEntity
import com.subhajit.mulberry.drawing.data.local.CanvasTextElementEntity
import com.subhajit.mulberry.drawing.data.local.DrawingDatabase
import com.subhajit.mulberry.drawing.data.local.DrawingDao
import com.subhajit.mulberry.drawing.data.local.toDomain
import com.subhajit.mulberry.drawing.data.mergeElementsByZIndex
import com.subhajit.mulberry.drawing.geometry.denormalizeToSurface
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasPaintItem
import com.subhajit.mulberry.drawing.model.CanvasPhotoElement
import com.subhajit.mulberry.photos.CanvasPhotoRenderer
import com.subhajit.mulberry.photos.CanvasPhotoStore
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.canvasPaintOrder
import com.subhajit.mulberry.drawing.render.CanvasStrokeRenderMode
import com.subhajit.mulberry.drawing.render.committedStrokeBitmapRenderer
import com.subhajit.mulberry.drawing.text.CanvasFontResolver
import com.subhajit.mulberry.stickers.StickerAssetStore
import com.subhajit.mulberry.stickers.StickerAssetVariant
import com.subhajit.mulberry.stickers.resolveStickerRenderSizePx
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class DefaultCanvasSnapshotRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DrawingDatabase,
    private val drawingDao: DrawingDao,
    private val canvasTextElementDao: CanvasTextElementDao,
    private val canvasStickerElementDao: CanvasStickerElementDao,
    private val canvasPhotoElementDao: CanvasPhotoElementDao,
    private val photoStore: CanvasPhotoStore,
    private val canvasMetadataDao: CanvasMetadataDao,
    private val stickerAssetStore: StickerAssetStore,
    private val sessionBootstrapRepository: SessionBootstrapRepository
) : CanvasSnapshotRenderer {

    override suspend fun renderCurrentSnapshot(): SnapshotRenderResult = withContext(Dispatchers.IO) {
        val strokeRenderMode = sessionBootstrapRepository.state.first().canvasStrokeRenderMode
        val dimensions = resolveSnapshotDimensions()
        val snapshotFile = WallpaperFiles.snapshotFile(context)
        snapshotFile.parentFile?.mkdirs()
        val tempSnapshotFile = File(snapshotFile.parentFile, "${snapshotFile.name}.tmp")
        var capturedMetadata = CanvasMetadataEntity.default()
        var strokes = emptyList<Stroke>()
        var textElements = emptyList<CanvasTextElementEntity>()
        var stickerElements = emptyList<CanvasStickerElementEntity>()
        var photoElements = emptyList<CanvasPhotoElementEntity>()

        database.withTransaction {
            capturedMetadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
            strokes = drawingDao.getStrokeGraphs().map { it.toDomain() }
            textElements = canvasTextElementDao.getElements()
            stickerElements = canvasStickerElementDao.getElements()
            photoElements = canvasPhotoElementDao.getElements()
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
        val missingStickerAssets = drawCanvas(
            canvas = canvas,
            elements = mergeElementsByZIndex(textElements, stickerElements, photoElements),
            strokes = strokes,
            placement = placement,
            strokeRenderMode = strokeRenderMode
        )

        runCatching { tempSnapshotFile.delete() }
        val wrote = FileOutputStream(tempSnapshotFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        if (!wrote) {
            runCatching { tempSnapshotFile.delete() }
            error("Unable to write wallpaper snapshot PNG")
        }
        if (!tempSnapshotFile.renameTo(snapshotFile)) {
            // Best-effort fallback: try replacing the target then renaming.
            runCatching { snapshotFile.delete() }
            if (!tempSnapshotFile.renameTo(snapshotFile)) {
                runCatching { tempSnapshotFile.delete() }
                error("Unable to move wallpaper snapshot into place")
            }
        }
        bitmap.recycle()

        val renderedAt = System.currentTimeMillis()
        database.withTransaction {
            val latestMetadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
            val snapshotMatchesCurrent = latestMetadata.revision == capturedMetadata.revision && !missingStickerAssets
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
            val snapshotFile = WallpaperFiles.snapshotFile(context)
            snapshotFile.delete()
            File(snapshotFile.parentFile, "${snapshotFile.name}.tmp").delete()
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

    /**
     * Draws the canvas bottom to top, as every client does: stickers, then text, then the strokes
     * over them in the order they were finished. Returns whether any sticker's artwork was missing.
     */
    private suspend fun drawCanvas(
        canvas: Canvas,
        elements: List<CanvasElement>,
        strokes: List<Stroke>,
        placement: SnapshotPlacement,
        strokeRenderMode: CanvasStrokeRenderMode
    ): Boolean {
        val strokeRenderer = strokeRenderMode.committedStrokeBitmapRenderer()
        val fontResolver by lazy { CanvasFontResolver.create(context) }
        var missingStickerAssets = false
        canvasPaintOrder(elements = elements, committedStrokes = strokes).forEach { item ->
            when (item) {
                is CanvasPaintItem.StrokeItem -> strokeRenderer.drawStroke(
                    canvas,
                    item.stroke.denormalizeToSurface(
                        width = placement.viewport.width,
                        height = placement.viewport.height,
                        offsetX = placement.offsetX,
                        offsetY = placement.offsetY
                    )
                )
                is CanvasPaintItem.Element -> when (val element = item.element) {
                    is CanvasTextElement -> drawTextElement(
                        canvas = canvas,
                        element = element,
                        placement = placement,
                        fontResolver = fontResolver
                    )
                    is CanvasStickerElement -> {
                        if (!drawStickerElement(canvas, element, placement)) missingStickerAssets = true
                    }
                    is CanvasPhotoElement -> {
                        // Downloaded here if need be, so a partner's photo shows without opening the app.
                        val image = photoStore.loadBitmap(element.photoId)
                        val cutout = element.cutoutPhotoId?.let { photoStore.loadBitmap(it) }
                        if (image == null) missingStickerAssets = true
                        CanvasPhotoRenderer.draw(
                            canvas = canvas,
                            photo = element,
                            image = image,
                            cutout = cutout,
                            canvasWidth = placement.viewport.width,
                            canvasHeight = placement.viewport.height,
                            offsetX = placement.offsetX,
                            offsetY = placement.offsetY
                        )
                    }
                }
            }
        }
        return missingStickerAssets
    }

    private fun drawTextElement(
        canvas: Canvas,
        element: CanvasTextElement,
        placement: SnapshotPlacement,
        fontResolver: CanvasFontResolver
    ) {
        val density = context.resources.displayMetrics.density
        val baseTextSizePx = 34f * density
        val pillPaddingPx = 12f * density
        val pillCornerPx = 18f * density
        val center = element.center.denormalizeToSurface(
            width = placement.viewport.width,
            height = placement.viewport.height,
            offsetX = placement.offsetX,
            offsetY = placement.offsetY
        )
        val wrapWidth = (element.boxWidth * placement.viewport.width).toInt().coerceAtLeast(1)
        val typeface = fontResolver.typefaceFor(element.font)
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

    /** Returns false when the sticker's artwork couldn't be found, and a placeholder stands in. */
    private suspend fun drawStickerElement(
        canvas: Canvas,
        element: CanvasStickerElement,
        placement: SnapshotPlacement
    ): Boolean {
        val density = context.resources.displayMetrics.density
        val center = element.center.denormalizeToSurface(
            width = placement.viewport.width,
            height = placement.viewport.height,
            offsetX = placement.offsetX,
            offsetY = placement.offsetY
        )
        val maxSizePx = (element.scale.coerceIn(0.08f, 1.6f) * placement.viewport.width).coerceAtLeast(1f)

        // Important for background sync: if the sticker isn't cached yet, attempt to
        // download it during snapshot rendering so the partner's wallpaper can render
        // correctly without requiring a foreground canvas session.
        val resolvedFile = runCatching {
            stickerAssetStore.getOrDownloadStickerAsset(
                packKey = element.packKey,
                packVersion = element.packVersion,
                stickerId = element.stickerId,
                variant = StickerAssetVariant.FULL
            )
        }.getOrNull()

        val bitmap = resolvedFile
            ?.takeIf { it.exists() && it.length() > 0 }
            ?.let { resolved -> android.graphics.BitmapFactory.decodeFile(resolved.absolutePath) }

        val renderSize = if (bitmap != null) {
            resolveStickerRenderSizePx(
                maxSizePx = maxSizePx,
                bitmapWidthPx = bitmap.width,
                bitmapHeightPx = bitmap.height
            )
        } else {
            com.subhajit.mulberry.stickers.StickerRenderSizePx(
                widthPx = maxSizePx,
                heightPx = maxSizePx
            )
        }
        val left = -renderSize.widthPx / 2f
        val top = -renderSize.heightPx / 2f
        val rect = RectF(left, top, left + renderSize.widthPx, top + renderSize.heightPx)

        canvas.save()
        canvas.translate(center.x, center.y)
        canvas.rotate((element.rotationRad * 180f / Math.PI).toFloat())

        if (bitmap != null) {
            val src = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
            canvas.drawBitmap(bitmap, src, rect, paint)
        } else {
            val placeholderPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.argb(64, 255, 255, 255)
            }
            canvas.drawRoundRect(rect, 18f * density, 18f * density, placeholderPaint)
        }

        canvas.restore()
        return bitmap != null
    }

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
