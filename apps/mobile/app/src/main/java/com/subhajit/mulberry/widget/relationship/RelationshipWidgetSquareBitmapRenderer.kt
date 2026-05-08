package com.subhajit.mulberry.widget.relationship

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.res.ResourcesCompat
import com.subhajit.mulberry.R
import kotlin.math.roundToInt

internal object RelationshipWidgetSquareBitmapRenderer {
    private const val DESIGN_WIDTH = 306f
    private const val DESIGN_HEIGHT = 348f

    private val PRIMARY_GRADIENT_START = Color.parseColor("#D81012")
    private val PRIMARY_GRADIENT_END = Color.parseColor("#B31329")
    private val TRACK_COLOR = Color.parseColor("#FDE6EA")
    private val PROGRESS_COLOR = Color.parseColor("#FA262B")

    fun render(
        context: Context,
        primaryText: String,
        secondaryText: String?,
        progressFraction: Float,
        isCelebratory: Boolean,
        primaryUsesGradient: Boolean
    ): Bitmap {
        val layout = LayoutMetrics(width = DESIGN_WIDTH, height = DESIGN_HEIGHT)
        val bitmap = Bitmap.createBitmap(
            DESIGN_WIDTH.roundToInt(),
            DESIGN_HEIGHT.roundToInt(),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.WHITE)
        drawDoodles(context, canvas, layout)
        drawSquirrel(context, canvas, isCelebratory, layout)
        drawDonut(context, canvas, progressFraction, layout)
        drawTextStack(context, canvas, primaryText, secondaryText, primaryUsesGradient, layout)

        return bitmap
    }

    private fun drawDoodles(
        context: Context,
        canvas: Canvas,
        layout: LayoutMetrics
    ) {
        val doodles = BitmapFactory.decodeResource(context.resources, R.drawable.widget_relationship_doodles) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            alpha = (255 * 0.2f).toInt()
        }
        canvas.drawBitmap(doodles, null, RectF(0f, 0f, layout.width, layout.height), paint)
    }

    private fun drawSquirrel(
        context: Context,
        canvas: Canvas,
        isCelebratory: Boolean,
        layout: LayoutMetrics
    ) {
        val squirrel = BitmapFactory.decodeResource(
            context.resources,
            if (isCelebratory) R.drawable.widget_squee_celebratory_square else R.drawable.widget_squee_default_square
        ) ?: return

        val destination = layout.rectFromDesign(-36f, -20f, 224f, 249f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(squirrel, null, destination, paint)
    }

    private fun drawDonut(
        context: Context,
        canvas: Canvas,
        progressFraction: Float,
        layout: LayoutMetrics
    ) {
        val size = layout.scale(106f)
        val left = layout.rightEdge - size
        val top = layout.y(126f)
        val stroke = size * 0.15f
        val centerX = left + size / 2f
        val centerY = top + size / 2f
        val bounds = RectF(
            left + stroke / 2f,
            top + stroke / 2f,
            left + size - stroke / 2f,
            top + size - stroke / 2f
        )

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.BUTT
        }

        ringPaint.color = TRACK_COLOR
        canvas.drawArc(bounds, -90f, 360f, false, ringPaint)

        ringPaint.color = PROGRESS_COLOR
        canvas.drawArc(bounds, -90f, progressFraction.coerceIn(0f, 1f) * 360f, false, ringPaint)

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(centerX, centerY, (size / 2f) - stroke, centerPaint)

        val heart = BitmapFactory.decodeResource(context.resources, R.drawable.widget_relationship_heart) ?: return
        val heartSize = size * 0.5f
        val heartLeft = centerX - heartSize / 2f
        val heartTop = centerY - heartSize / 2f
        val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(
            heart,
            null,
            RectF(heartLeft, heartTop, heartLeft + heartSize, heartTop + heartSize),
            heartPaint
        )
    }

    private fun drawTextStack(
        context: Context,
        canvas: Canvas,
        primaryText: String,
        secondaryText: String?,
        primaryUsesGradient: Boolean,
        layout: LayoutMetrics
    ) {
        val primaryTypeface = ResourcesCompat.getFont(context, R.font.poppins_extrabold)
        val secondaryTypeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
        val rightEdge = layout.rightEdge
        val maxPrimaryWidth = rightEdge - layout.x(8f)

        val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = primaryTypeface
            textAlign = Paint.Align.RIGHT
        }
        primaryPaint.textSize = fitTextSize(
            paint = primaryPaint,
            desiredPx = layout.scale(60f),
            minPx = layout.scale(36f),
            text = primaryText,
            maxWidthPx = maxPrimaryWidth
        )

        if (primaryUsesGradient) {
            val textWidth = primaryPaint.measureText(primaryText).coerceAtLeast(1f)
            primaryPaint.shader = LinearGradient(
                rightEdge - textWidth,
                0f,
                rightEdge,
                0f,
                PRIMARY_GRADIENT_START,
                PRIMARY_GRADIENT_END,
                Shader.TileMode.CLAMP
            )
        } else {
            primaryPaint.color = PRIMARY_GRADIENT_START
        }

        canvas.drawText(primaryText, rightEdge, layout.y(288f), primaryPaint)

        val secondary = secondaryText.orEmpty()
        if (secondary.isBlank()) return

        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = secondaryTypeface
            textAlign = Paint.Align.RIGHT
            color = Color.BLACK
        }
        secondaryPaint.textSize = fitTextSize(
            paint = secondaryPaint,
            desiredPx = layout.scale(27f),
            minPx = layout.scale(19f),
            text = secondary,
            maxWidthPx = maxPrimaryWidth
        )

        canvas.drawText(secondary, rightEdge, layout.y(322f), secondaryPaint)
    }

    private fun fitTextSize(
        paint: Paint,
        desiredPx: Float,
        minPx: Float,
        text: String,
        maxWidthPx: Float
    ): Float {
        paint.textSize = desiredPx
        val width = paint.measureText(text)
        if (width <= maxWidthPx || maxWidthPx <= 0f) return desiredPx
        return (desiredPx * (maxWidthPx / width)).coerceAtLeast(minPx)
    }

    private data class LayoutMetrics(
        val width: Float,
        val height: Float
    ) {
        private val scale = width / DESIGN_WIDTH

        val rightEdge: Float = width - scale(16f)

        fun scale(value: Float): Float = value * scale

        fun x(value: Float): Float = value * scale

        fun y(value: Float): Float = value * scale

        fun rectFromDesign(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float
        ): RectF = RectF(x(left), y(top), x(right), y(bottom))
    }
}
