package com.subhajit.mulberry.drawing.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DryBrushStrokeRenderer : StrokeBitmapRenderer, StrokeVisualRenderer {
    override fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.size < 2) {
            RoundStrokeRenderer.drawStroke(canvas, stroke)
            return
        }

        val color = stroke.colorArgb.toInt()
        drawBody(canvas, stroke, color)
        drawBristles(canvas, stroke, color)
    }

    override fun DrawScope.drawStroke(stroke: Stroke) {
        drawIntoCanvas { canvas ->
            drawStroke(canvas.nativeCanvas, stroke)
        }
    }

    private fun drawBody(canvas: Canvas, stroke: Stroke, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = max(1.5f, stroke.width * 0.42f)
            this.color = color.withAlpha(132)
        }
        canvas.drawPath(stroke.points.toSmoothPath(), paint)
    }

    private fun drawBristles(canvas: Canvas, stroke: Stroke, color: Int) {
        val bristleCount = (stroke.width / 2.6f).roundToInt().coerceIn(6, 14)
        val bristleWidth = max(0.85f, stroke.width / (bristleCount * 1.75f))
        val halfWidth = stroke.width / 2f

        repeat(bristleCount) { index ->
            val lane = if (bristleCount == 1) {
                0f
            } else {
                -halfWidth + ((index.toFloat() / (bristleCount - 1).toFloat()) * stroke.width)
            }
            val seed = stroke.seed(index)
            val jitter = ((seed and 0xFF) / 255f - 0.5f) * stroke.width * 0.24f
            val alpha = 164 + ((seed ushr 8) % 70)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.SQUARE
                strokeJoin = Paint.Join.ROUND
                strokeWidth = bristleWidth * (0.78f + (((seed ushr 16) and 0xFF) / 255f) * 0.7f)
                this.color = color.withAlpha(alpha)
            }
            canvas.drawPath(
                stroke.points.toBristlePath(
                    laneOffset = lane + jitter,
                    seed = seed,
                    strokeWidth = stroke.width
                ),
                paint
            )
        }
    }

    private fun List<StrokePoint>.toSmoothPath(): Path = Path().apply {
        moveTo(first().x, first().y)
        for (index in 1 until lastIndex) {
            val point = this@toSmoothPath[index]
            val next = this@toSmoothPath[index + 1]
            quadTo(point.x, point.y, (point.x + next.x) / 2f, (point.y + next.y) / 2f)
        }
        lineTo(last().x, last().y)
    }

    private fun List<StrokePoint>.toBristlePath(
        laneOffset: Float,
        seed: Int,
        strokeWidth: Float
    ): Path {
        val path = Path()
        var drawing = false
        val startTrim = seed % 3
        val endTrim = (seed ushr 3) % 4

        forEachIndexed { index, point ->
            val noise = seededNoise(seed, index)
            val shouldGap = index > startTrim &&
                index < lastIndex - endTrim &&
                noise < DRY_GAP_RATE
            if (shouldGap) {
                drawing = false
                return@forEachIndexed
            }

            val normal = normalAt(index)
            val tangent = tangentAt(index)
            val feather = ((seededNoise(seed xor 0x6D2B79F5, index) - 0.5f) * strokeWidth * 0.18f)
            val x = point.x + (normal.x * laneOffset) + (tangent.x * feather)
            val y = point.y + (normal.y * laneOffset) + (tangent.y * feather)
            if (!drawing) {
                path.moveTo(x, y)
                drawing = true
            } else {
                path.lineTo(x, y)
            }
        }

        return path
    }

    private fun List<StrokePoint>.normalAt(index: Int): Vector {
        val tangent = tangentAt(index)
        return Vector(x = -tangent.y, y = tangent.x)
    }

    private fun List<StrokePoint>.tangentAt(index: Int): Vector {
        val previous = this[max(0, index - 1)]
        val next = this[min(lastIndex, index + 1)]
        val dx = next.x - previous.x
        val dy = next.y - previous.y
        val length = hypot(dx, dy).takeIf { it > 0.001f } ?: 1f
        return Vector(x = dx / length, y = dy / length)
    }

    private fun Stroke.seed(index: Int): Int {
        var hash = id.fold(0x45D9F3B) { acc, char -> (acc * 31) xor char.code }
        hash = (hash * 31) xor colorArgb.toInt()
        hash = (hash * 31) xor width.toBits()
        hash = (hash * 31) xor index
        return hash.mix() and Int.MAX_VALUE
    }

    private fun seededNoise(seed: Int, index: Int): Float {
        val mixed = (seed xor (index * 0x9E3779B9.toInt())).mix()
        return ((mixed ushr 1) and 0x7FFFFFFF) / Int.MAX_VALUE.toFloat()
    }

    private fun Int.mix(): Int {
        var value = this
        value = value xor (value ushr 16)
        value *= 0x7FEB352D
        value = value xor (value ushr 15)
        value *= 0x846CA68B.toInt()
        value = value xor (value ushr 16)
        return value
    }

    private fun Int.withAlpha(alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(this),
        Color.green(this),
        Color.blue(this)
    )

    private data class Vector(val x: Float, val y: Float)

    private const val DRY_GAP_RATE = 0.085f
}
