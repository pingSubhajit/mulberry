package com.subhajit.mulberry.widget.relationship

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.subhajit.mulberry.R
import kotlin.math.roundToInt

internal object RelationshipWidgetProgressDonutRenderer {
    private val TRACK_COLOR = Color.parseColor("#FDE6EA")
    private val PROGRESS_COLOR = Color.parseColor("#FA262B")
    private val CENTER_COLOR = Color.WHITE

    fun render(
        context: Context,
        sizeDp: Int,
        progressFraction: Float
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(1)
        val strokePx = sizePx * 0.15f
        val inset = strokePx / 2f
        val bounds = RectF(inset, inset, sizePx - inset, sizePx - inset)
        val progress = progressFraction.coerceIn(0f, 1f)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.BUTT
        }

        ringPaint.color = TRACK_COLOR
        canvas.drawArc(bounds, -90f, 360f, false, ringPaint)

        ringPaint.color = PROGRESS_COLOR
        canvas.drawArc(bounds, -90f, progress * 360f, false, ringPaint)

        val centerRadius = (sizePx / 2f) - strokePx
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CENTER_COLOR
            style = Paint.Style.FILL
        }
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, centerRadius, centerPaint)

        drawHeart(context, canvas, sizePx)

        return bitmap
    }

    private fun drawHeart(context: Context, canvas: Canvas, sizePx: Int) {
        val heart = BitmapFactory.decodeResource(
            context.resources,
            R.drawable.widget_relationship_heart
        ) ?: return

        val heartSize = sizePx * 0.5f
        val left = (sizePx - heartSize) / 2f
        val top = (sizePx - heartSize) / 2f
        val destination = RectF(left, top, left + heartSize, top + heartSize)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(heart, null, destination, paint)
    }
}
