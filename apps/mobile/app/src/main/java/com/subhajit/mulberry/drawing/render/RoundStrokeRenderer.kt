package com.subhajit.mulberry.drawing.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as ComposeStroke
import com.subhajit.mulberry.drawing.model.Stroke

object RoundStrokeRenderer : StrokeBitmapRenderer, StrokeVisualRenderer {
    override fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.colorArgb.toInt()
            strokeWidth = stroke.width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            val point = stroke.points.first()
            canvas.drawCircle(point.x, point.y, stroke.width / 2f, paint)
            return
        }

        paint.style = Paint.Style.STROKE
        val path = Path().apply {
            moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { point ->
                lineTo(point.x, point.y)
            }
        }
        canvas.drawPath(path, paint)
    }

    override fun DrawScope.drawStroke(stroke: Stroke) {
        if (stroke.points.isEmpty()) return

        val color = Color(stroke.colorArgb)
        if (stroke.points.size == 1) {
            drawCircle(
                color = color,
                radius = stroke.width / 2f,
                center = Offset(stroke.points.first().x, stroke.points.first().y)
            )
            return
        }

        val path = ComposePath().apply {
            moveTo(stroke.points.first().x, stroke.points.first().y)
            stroke.points.drop(1).forEach { point ->
                lineTo(point.x, point.y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = ComposeStroke(
                width = stroke.width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}
