package com.subhajit.elaris.drawing.engine

import com.subhajit.elaris.drawing.model.BrushStyle
import com.subhajit.elaris.drawing.model.Stroke
import com.subhajit.elaris.drawing.model.StrokePoint
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

class StrokeBuilder @Inject constructor() {
    fun startStroke(
        point: StrokePoint,
        brushStyle: BrushStyle,
        createdAt: Long = System.currentTimeMillis()
    ): Stroke = Stroke(
        id = UUID.randomUUID().toString(),
        colorArgb = brushStyle.colorArgb,
        width = brushStyle.width,
        points = listOf(point),
        createdAt = createdAt
    )

    fun appendPoint(stroke: Stroke, point: StrokePoint): Stroke {
        val lastPoint = stroke.points.lastOrNull()
        if (lastPoint != null && isEffectivelySamePoint(lastPoint, point)) {
            return stroke
        }
        return stroke.copy(points = stroke.points + point)
    }

    fun finishStroke(stroke: Stroke): Stroke? = if (stroke.points.isEmpty()) null else stroke

    private fun isEffectivelySamePoint(
        first: StrokePoint,
        second: StrokePoint
    ): Boolean = abs(first.x - second.x) < 0.5f && abs(first.y - second.y) < 0.5f
}
