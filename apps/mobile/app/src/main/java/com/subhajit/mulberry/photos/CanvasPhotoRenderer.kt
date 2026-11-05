package com.subhajit.mulberry.photos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.subhajit.mulberry.drawing.model.CanvasPhotoElement
import com.subhajit.mulberry.drawing.model.CanvasPhotoStyle
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A photo's look on the canvas, the same as iOS and the Mac draw it (`CanvasPhotoGeometry`). The
 * framed photo's long edge is `scale` of the canvas width. Frames are white paper with a thin
 * border; a polaroid's bottom edge is deeper; a sticker is the cut-out subject with its white
 * outline baked in. Every style casts a soft shadow so it lifts off what is beneath.
 */
object CanvasPhotoRenderer {
    /** The paper's colour, a touch warm so it never glows on a white canvas. */
    const val PAPER_ARGB: Int = 0xFFFCFAF6.toInt()
    private const val PLACEHOLDER_ARGB: Int = 0xFFD1D1D1.toInt()
    private const val SHADOW_ALPHA = 0.3f

    /** The framed photo laid out with its long edge `long`: the outer size, and where the photo sits in it. */
    data class Layout(
        val width: Float,
        val height: Float,
        val photo: RectF,
        val cornerRadius: Float,
        val photoCornerRadius: Float
    )

    data class Shadow(val offsetY: Float, val blur: Float)

    /** A photo drawn upright with its shadow, centred on its own middle, [width] by [height] canvas pixels. */
    data class Rendered(val bitmap: Bitmap, val width: Float, val height: Float)

    /** Insets in units of the photo's short side: top, side and bottom. */
    private fun insets(style: CanvasPhotoStyle): Triple<Float, Float, Float> = when (style) {
        CanvasPhotoStyle.FRAME -> Triple(0.05f, 0.05f, 0.05f)
        CanvasPhotoStyle.POLAROID -> Triple(0.07f, 0.07f, 0.3f)
        // Without its cut-out, a sticker falls back to a rounded, thick-bordered print.
        CanvasPhotoStyle.STICKER -> Triple(0.06f, 0.06f, 0.06f)
    }

    fun layout(style: CanvasPhotoStyle, aspectRatio: Float, long: Float): Layout {
        val aspect = if (aspectRatio > 0f && aspectRatio.isFinite()) aspectRatio else 1f
        // A photo of short side 1, then scaled so the outer long edge is `long`.
        val photoWidth = if (aspect >= 1f) aspect else 1f
        val photoHeight = if (aspect >= 1f) 1f else 1f / aspect
        val (top, side, bottom) = insets(style)
        val outerWidth = photoWidth + 2 * side
        val outerHeight = photoHeight + top + bottom
        val unit = long / max(outerWidth, outerHeight)
        val corner = when (style) {
            CanvasPhotoStyle.STICKER -> 0.08f
            CanvasPhotoStyle.POLAROID -> 0.008f
            CanvasPhotoStyle.FRAME -> 0.012f
        }
        return Layout(
            width = outerWidth * unit,
            height = outerHeight * unit,
            photo = RectF(side * unit, top * unit, (side + photoWidth) * unit, (top + photoHeight) * unit),
            cornerRadius = corner * long,
            photoCornerRadius = if (style == CanvasPhotoStyle.STICKER) 0.05f * long else 0.003f * long
        )
    }

    fun longEdge(photo: CanvasPhotoElement, canvasWidth: Float): Float = max(1f, photo.renderScale * canvasWidth)

    /** The drawn size: the cut-out's own shape for a sticker, or the frame. */
    fun size(photo: CanvasPhotoElement, cutout: Bitmap?, canvasWidth: Float): Pair<Float, Float> {
        val long = longEdge(photo, canvasWidth)
        if (photo.style == CanvasPhotoStyle.STICKER && cutout != null && cutout.width > 0 && cutout.height > 0) {
            val aspect = cutout.width.toFloat() / cutout.height
            return if (aspect >= 1f) long to long / aspect else long * aspect to long
        }
        val layout = layout(photo.style, photo.aspectRatio, long)
        return layout.width to layout.height
    }

    fun shadow(long: Float): Shadow = Shadow(offsetY = 0.014f * long, blur = 0.06f * long)

    /**
     * The photo upright with its shadow, as a bitmap. Blurring a shadow is most of a photo's cost,
     * so the canvas renders each photo once and draws the result while only its place or angle
     * changes, as on iOS. Null when it would be too big to hold.
     */
    fun render(photo: CanvasPhotoElement, image: Bitmap?, cutout: Bitmap?, canvasWidth: Float): Rendered? {
        val long = longEdge(photo, canvasWidth)
        val shadow = shadow(long)
        val (drawnWidth, drawnHeight) = size(photo, cutout, canvasWidth)
        val pad = ceil(shadow.blur * 1.5f + shadow.offsetY)
        val width = drawnWidth + pad * 2
        val height = drawnHeight + pad * 2
        val pixelsWide = ceil(width).toInt()
        val pixelsHigh = ceil(height).toInt()
        if (pixelsWide <= 0 || pixelsHigh <= 0 || pixelsWide.toLong() * pixelsHigh >= 40_000_000L) return null
        val bitmap = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(pixelsWide / 2f, pixelsHigh / 2f)
        drawUpright(canvas, photo, image, cutout, long)
        return Rendered(bitmap, pixelsWide.toFloat(), pixelsHigh.toFloat())
    }

    /**
     * Draws the photo straight onto a software [canvas] of [canvasWidth] by [canvasHeight], whose
     * top left is at ([offsetX], [offsetY]). The wallpaper draws this way.
     */
    fun draw(
        canvas: Canvas,
        photo: CanvasPhotoElement,
        image: Bitmap?,
        cutout: Bitmap?,
        canvasWidth: Float,
        canvasHeight: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ) {
        val long = longEdge(photo, canvasWidth)
        canvas.save()
        canvas.translate(offsetX + photo.center.x * canvasWidth, offsetY + photo.center.y * canvasHeight)
        canvas.rotate(Math.toDegrees(photo.rotationRad.toDouble()).toFloat())
        drawUpright(canvas, photo, image, cutout, long)
        canvas.restore()
    }

    /** Whether ([x], [y]) in canvas pixels is on the photo, at least [minTarget] square to aim at. */
    fun contains(
        x: Float,
        y: Float,
        photo: CanvasPhotoElement,
        cutout: Bitmap?,
        canvasWidth: Float,
        canvasHeight: Float,
        minTarget: Float
    ): Boolean {
        val (width, height) = size(photo, cutout, canvasWidth)
        val dx = x - photo.center.x * canvasWidth
        val dy = y - photo.center.y * canvasHeight
        val cosine = cos(photo.rotationRad)
        val sine = sin(photo.rotationRad)
        val localX = cosine * dx + sine * dy
        val localY = -sine * dx + cosine * dy
        val halfWidth = max(minTarget, width) / 2f
        val halfHeight = max(minTarget, height) / 2f
        return localX in -halfWidth..halfWidth && localY in -halfHeight..halfHeight
    }

    /**
     * The colour of the photo at ([x], [y]) in canvas pixels, as the eyedropper picks it: the
     * picture, or the paper around it. Null off the photo, or on a cut-out's clear background.
     */
    fun colorAt(
        x: Float,
        y: Float,
        photo: CanvasPhotoElement,
        image: Bitmap?,
        cutout: Bitmap?,
        canvasWidth: Float,
        canvasHeight: Float
    ): Int? {
        val dx = x - photo.center.x * canvasWidth
        val dy = y - photo.center.y * canvasHeight
        val cosine = cos(photo.rotationRad)
        val sine = sin(photo.rotationRad)
        val localX = cosine * dx + sine * dy
        val localY = -sine * dx + cosine * dy
        val (width, height) = size(photo, cutout, canvasWidth)
        if (localX !in -width / 2..width / 2 || localY !in -height / 2..height / 2) return null
        if (photo.style == CanvasPhotoStyle.STICKER && cutout != null) {
            val pixel = pixelAt(cutout, RectF(-width / 2, -height / 2, width / 2, height / 2), localX, localY)
            return pixel.takeIf { Color.alpha(it) > 0 }
        }
        val layout = layout(photo.style, photo.aspectRatio, longEdge(photo, canvasWidth))
        val picture = RectF(layout.photo).apply { offset(-layout.width / 2, -layout.height / 2) }
        if (!picture.contains(localX, localY)) return PAPER_ARGB
        if (image == null || image.width <= 0 || image.height <= 0) return PLACEHOLDER_ARGB
        return pixelAt(image, aspectFill(image.width.toFloat(), image.height.toFloat(), picture), localX, localY)
    }

    private fun pixelAt(bitmap: Bitmap, drawnIn: RectF, x: Float, y: Float): Int {
        val px = ((x - drawnIn.left) / drawnIn.width() * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val py = ((y - drawnIn.top) / drawnIn.height() * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        return bitmap.getPixel(px, py)
    }

    /** The photo centred on the origin, unrotated. One not loaded yet shows its paper with a grey where the picture goes. */
    private fun drawUpright(canvas: Canvas, photo: CanvasPhotoElement, image: Bitmap?, cutout: Bitmap?, long: Float) {
        val shadow = shadow(long)
        val shadowColor = Color.argb((SHADOW_ALPHA * 255).toInt(), 0, 0, 0)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (photo.style == CanvasPhotoStyle.STICKER && cutout != null) {
            val (width, height) = size(photo, cutout, long / photo.renderScale)
            val rect = RectF(-width / 2, -height / 2, width / 2, height / 2)
            // The shadow follows the cut-out's own shape.
            val silhouette = cutout.extractAlpha()
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = shadowColor
                if (shadow.blur > 0f) {
                    maskFilter = android.graphics.BlurMaskFilter(shadow.blur / 2f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
            }
            canvas.drawBitmap(silhouette, null, RectF(rect).apply { offset(0f, shadow.offsetY) }, shadowPaint)
            silhouette.recycle()
            canvas.drawBitmap(cutout, null, rect, bitmapPaint)
            return
        }

        val layout = layout(photo.style, photo.aspectRatio, long)
        val left = -layout.width / 2
        val top = -layout.height / 2
        val paper = RectF(left, top, left + layout.width, top + layout.height)
        val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PAPER_ARGB
            setShadowLayer(shadow.blur / 2f, 0f, shadow.offsetY, shadowColor)
        }
        canvas.drawRoundRect(paper, layout.cornerRadius, layout.cornerRadius, paperPaint)

        val picture = RectF(layout.photo).apply { offset(left, top) }
        canvas.save()
        canvas.clipPath(Path().apply {
            addRoundRect(picture, layout.photoCornerRadius, layout.photoCornerRadius, Path.Direction.CW)
        })
        if (image != null && image.width > 0 && image.height > 0) {
            canvas.drawBitmap(image, null, aspectFill(image.width.toFloat(), image.height.toFloat(), picture), bitmapPaint)
        } else {
            canvas.drawColor(PLACEHOLDER_ARGB)
        }
        canvas.restore()
    }

    private fun aspectFill(imageWidth: Float, imageHeight: Float, rect: RectF): RectF {
        val scale = max(rect.width() / imageWidth, rect.height() / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return RectF(rect.centerX() - width / 2, rect.centerY() - height / 2, rect.centerX() + width / 2, rect.centerY() + height / 2)
    }
}
