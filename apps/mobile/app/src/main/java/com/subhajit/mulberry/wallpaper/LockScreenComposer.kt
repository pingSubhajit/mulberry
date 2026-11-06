package com.subhajit.mulberry.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt

/** How a layer of the lock screen fills the surface. */
internal enum class LockScreenLayerScale {
    /** The wallpaper background: fills the surface, centre-cropped. */
    CENTER_CROP_TO_CANVAS,

    /** The canvas snapshot: its centre, at the size of the screen. */
    CENTERED_SCREEN_VIEWPORT
}

/** What the lock screen is made of, bottom to top. */
internal data class LockScreenLayers(
    val fallbackColorArgb: Int,
    val background: Bitmap?,
    val snapshot: Bitmap?,
    /** Black over the background, from 0 to 1. The live wallpaper never dims; the share image's fallback does. */
    val backgroundDim: Float = 0f
)

/**
 * Draws the lock screen as Mulberry's live wallpaper shows it: the fallback colour, the background
 * centre-cropped to the surface, then the canvas snapshot centred on the screen. The live
 * wallpaper and the share image both draw through here, so the share image is exactly what the
 * lock screen shows.
 */
internal object LockScreenComposer {
    fun compose(
        canvas: Canvas,
        layers: LockScreenLayers,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        paint: Paint? = null
    ) {
        canvas.drawColor(layers.fallbackColorArgb)
        layers.background?.let { background ->
            drawLayer(canvas, background, LockScreenLayerScale.CENTER_CROP_TO_CANVAS, viewportWidthPx, viewportHeightPx, paint)
        }
        if (layers.backgroundDim > 0f) {
            canvas.drawColor(Color.argb((layers.backgroundDim.coerceIn(0f, 1f) * 255f).roundToInt(), 0, 0, 0))
        }
        layers.snapshot?.let { snapshot ->
            drawLayer(canvas, snapshot, LockScreenLayerScale.CENTERED_SCREEN_VIEWPORT, viewportWidthPx, viewportHeightPx, paint)
        }
    }

    fun drawLayer(
        canvas: Canvas,
        bitmap: Bitmap,
        scale: LockScreenLayerScale,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        paint: Paint? = null
    ) {
        val sourceRect = when (scale) {
            LockScreenLayerScale.CENTER_CROP_TO_CANVAS -> centerCropSourceRect(
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                targetWidth = canvas.width,
                targetHeight = canvas.height
            )
            LockScreenLayerScale.CENTERED_SCREEN_VIEWPORT -> centeredScreenSourceRect(
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                screenWidth = viewportWidthPx,
                screenHeight = viewportHeightPx
            )
        }
        val destinationRect = when (scale) {
            LockScreenLayerScale.CENTER_CROP_TO_CANVAS -> ScreenSourceRect(0, 0, canvas.width, canvas.height)
            LockScreenLayerScale.CENTERED_SCREEN_VIEWPORT -> centeredScreenSourceRect(
                bitmapWidth = canvas.width,
                bitmapHeight = canvas.height,
                screenWidth = viewportWidthPx,
                screenHeight = viewportHeightPx
            )
        }
        canvas.drawBitmap(bitmap, sourceRect.toRect(), destinationRect.toRect(), paint)
    }

    private fun ScreenSourceRect.toRect(): Rect = Rect(left, top, right, bottom)
}
