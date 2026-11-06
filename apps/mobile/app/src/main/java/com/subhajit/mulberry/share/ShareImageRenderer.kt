package com.subhajit.mulberry.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withMatrix
import com.subhajit.mulberry.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** The clock as the lock screen shows it, e.g. "Friday, 6 November" and "7:23". */
data class ShareClockText(val date: String, val time: String)

/**
 * Draws the share image per the rendering contract in docs/share-frames.md, matching the reference
 * renderer (temp/share-frames/v1/src/render_preview.py). Not thread-safe: call it from one
 * background coroutine at a time.
 */
@Singleton
class ShareImageRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val captionTypeface: Typeface by lazy { font(R.font.permanent_marker_regular) }
    private val mediumTypeface: Typeface by lazy { font(R.font.poppins_medium) }
    private val semiBoldTypeface: Typeface by lazy { font(R.font.poppins_semibold) }

    private val captionPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = captionTypeface }
    }
    private val backingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /** Measures caption lines in Permanent Marker, for [ShareCaptionFitter]. */
    val captionMeasurer = ShareTextMeasurer { text, fontSize ->
        captionPaint.textSize = fontSize.toFloat()
        captionPaint.measureText(text).toDouble()
    }

    /**
     * Draws the clock over the wallpaper layer, last, as the system lock screen does: the date in
     * Poppins Medium at 59px with its baseline at 11% of the height, the time in Poppins SemiBold
     * with its ink top at 13%, sized so "7:23" is 230px tall, both over a soft shadow.
     */
    fun drawClock(content: Canvas, clock: ShareClockText) {
        val centerX = SHARE_CONTENT_WIDTH / 2f
        val datePaint = clockPaint(mediumTypeface).apply { textSize = DATE_FONT_SIZE }
        content.drawText(clock.date, centerX, SHARE_CONTENT_HEIGHT * DATE_BASELINE_FRACTION, datePaint)

        val timePaint = clockPaint(semiBoldTypeface)
        timePaint.textSize = TIME_PROBE_SIZE
        val probe = Rect().also { timePaint.getTextBounds(TIME_PROBE_TEXT, 0, TIME_PROBE_TEXT.length, it) }
        timePaint.textSize = (TIME_PROBE_SIZE * TIME_INK_HEIGHT / probe.height().coerceAtLeast(1)).roundToInt().toFloat()
        val ink = Rect().also { timePaint.getTextBounds(clock.time, 0, clock.time.length, it) }
        content.drawText(clock.time, centerX, SHARE_CONTENT_HEIGHT * TIME_TOP_FRACTION - ink.top, timePaint)
    }

    /**
     * The content scaled close to its size in the quad, halving with filtering so the final map is
     * a gentle resample rather than a 2× minification.
     */
    fun prepareContentForQuad(content: Bitmap, quad: ShareQuad): Bitmap {
        val targetWidth = hypot(quad.topRight.x - quad.topLeft.x, quad.topRight.y - quad.topLeft.y)
        var current = content
        while (current.width / 2 >= targetWidth && current.width / 2 > 0) {
            val next = current.scale(current.width / 2, current.height / 2, filter = true)
            if (current !== content) current.recycle()
            current = next
        }
        return current
    }

    /** The 1080×1920 share image: content through the quad, then the frame, caption and brand. */
    fun render(content: Bitmap, frame: Bitmap, layout: ShareFrameLayout, caption: String): Bitmap {
        val output = createBitmap(SHARE_OUTPUT_WIDTH, SHARE_OUTPUT_HEIGHT)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val (source, destination) = ShareGeometry.polyToPolyPoints(
            layout.content.quad,
            content.width.toFloat(),
            content.height.toFloat()
        )
        val contentMatrix = Matrix().apply { setPolyToPoly(source, 0, destination, 0, 3) }
        canvas.drawBitmap(content, contentMatrix, bitmapPaint)

        canvas.drawBitmap(
            frame,
            Rect(0, 0, frame.width, frame.height),
            Rect(0, 0, SHARE_OUTPUT_WIDTH, SHARE_OUTPUT_HEIGHT),
            bitmapPaint
        )
        drawCaption(canvas, caption, layout.caption)
        drawBrand(canvas, layout.brand)
        return output
    }

    private fun drawCaption(canvas: Canvas, text: String, spec: ShareFrameCaption) {
        val fit = ShareCaptionFitter.fit(text, spec, captionMeasurer)
        if (fit.lines.isEmpty()) return
        captionPaint.textSize = fit.fontSize.toFloat()
        captionPaint.color = spec.inkColorArgb
        val inks = fit.lines.map { line -> Rect().also { captionPaint.getTextBounds(line, 0, line.length, it) } }
        val paddingX = spec.backing?.paddingX ?: 0.0
        val paddingY = spec.backing?.paddingY ?: 0.0
        val advance = fit.fontSize * spec.lineHeight
        val blockWidth = ceil(inks.maxOf { it.width() } + 2 * paddingX)
        val blockHeight = ceil((fit.lines.size - 1) * advance + inks.maxOf { it.height() } + 2 * paddingY)

        withBlockPlacement(canvas, spec.position, spec.anchor, spec.rotationDegrees, blockWidth, blockHeight) {
            fit.lines.forEachIndexed { index, line ->
                val ink = inks[index]
                val x = when (spec.align) {
                    ShareCaptionAlign.LEFT -> paddingX
                    ShareCaptionAlign.RIGHT -> blockWidth - paddingX - ink.width()
                }
                val y = paddingY + index * advance
                spec.backing?.let { backing ->
                    backingPaint.color = backing.colorArgb
                    canvas.drawRoundRect(
                        RectF(
                            (x - paddingX).toFloat(),
                            (y - paddingY).toFloat(),
                            (x + ink.width() + paddingX).toFloat(),
                            (y + ink.height() + paddingY).toFloat()
                        ),
                        CAPTION_STRIP_RADIUS,
                        CAPTION_STRIP_RADIUS,
                        backingPaint
                    )
                }
                canvas.drawText(line, (x - ink.left).toFloat(), (y - ink.top).toFloat(), captionPaint)
            }
        }
    }

    private fun drawBrand(canvas: Canvas, spec: ShareFrameBrand) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = mediumTypeface
            textSize = spec.fontSize.toFloat()
            color = spec.colorArgb
        }
        val ink = Rect().also { textPaint.getTextBounds(BRAND_TEXT, 0, BRAND_TEXT.length, it) }
        val mark = brandMark(spec.colorArgb)
        val markHeight = spec.markHeight.roundToInt()
        val markWidth = mark?.let {
            (markHeight * it.intrinsicWidth.toDouble() / it.intrinsicHeight.coerceAtLeast(1)).roundToInt()
        } ?: 0
        val gap = if (mark != null) BRAND_GAP else 0
        val paddingX = spec.backing?.paddingX ?: 0.0
        val paddingY = spec.backing?.paddingY ?: 0.0
        val blockWidth = markWidth + gap + ink.width() + 2 * paddingX
        val blockHeight = max(markHeight, ink.height()) + 2 * paddingY

        withBlockPlacement(canvas, spec.position, spec.anchor, spec.rotationDegrees, blockWidth, blockHeight) {
            spec.backing?.let { backing ->
                backingPaint.color = backing.colorArgb
                val radius = backing.cornerRadius.toFloat()
                canvas.drawRoundRect(RectF(0f, 0f, blockWidth.toFloat(), blockHeight.toFloat()), radius, radius, backingPaint)
            }
            mark?.let {
                val left = paddingX.roundToInt()
                val top = ((blockHeight - markHeight) / 2).toInt()
                it.setBounds(left, top, left + markWidth, top + markHeight)
                it.draw(canvas)
            }
            canvas.drawText(
                BRAND_TEXT,
                (paddingX + markWidth + gap - ink.left).toFloat(),
                ((blockHeight - ink.height()) / 2 - ink.top).toFloat(),
                textPaint
            )
        }
    }

    private inline fun withBlockPlacement(
        canvas: Canvas,
        position: SharePoint,
        anchor: ShareBlockAnchor,
        rotationDegrees: Double,
        width: Double,
        height: Double,
        draw: () -> Unit
    ) {
        val placement = ShareGeometry.blockPlacement(position, anchor, rotationDegrees, width, height)
        canvas.withMatrix(Matrix().apply { setValues(placement.toMatrixValues()) }) { draw() }
    }

    private fun brandMark(colorArgb: Int): Drawable? =
        ContextCompat.getDrawable(context, R.drawable.brand_iconmark_color)?.mutate()?.apply { setTint(colorArgb) }

    private fun clockPaint(typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(CLOCK_SHADOW_RADIUS, 0f, CLOCK_SHADOW_OFFSET_Y, CLOCK_SHADOW_COLOR)
    }

    private fun font(resId: Int): Typeface = ResourcesCompat.getFont(context, resId) ?: Typeface.DEFAULT

    private companion object {
        const val DATE_FONT_SIZE = 59f
        const val DATE_BASELINE_FRACTION = 0.11f
        const val TIME_TOP_FRACTION = 0.13f
        const val TIME_PROBE_TEXT = "7:23"
        const val TIME_PROBE_SIZE = 320f
        const val TIME_INK_HEIGHT = 230f

        // The reference blurs with a Gaussian of standard deviation 4. Android's shadow radius
        // becomes a sigma of 0.57735 × radius + 0.5, so this radius gives the same blur.
        const val CLOCK_SHADOW_RADIUS = 6.06f
        const val CLOCK_SHADOW_OFFSET_Y = 3f
        const val CLOCK_SHADOW_COLOR = 0x4D000000 // black at 30%

        const val CAPTION_STRIP_RADIUS = 7f
        const val BRAND_TEXT = "mulberry.my"
        const val BRAND_GAP = 13
    }
}
