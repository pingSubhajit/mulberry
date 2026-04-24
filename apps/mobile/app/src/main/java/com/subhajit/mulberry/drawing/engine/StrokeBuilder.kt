package com.subhajit.mulberry.drawing.engine

import com.subhajit.mulberry.drawing.model.BrushStyle
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
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
        return appendPoint(stroke, point, DEFAULT_SAME_POINT_THRESHOLD)
    }

    fun appendPoint(
        stroke: Stroke,
        point: StrokePoint,
        samePointThreshold: Float
    ): Stroke {
        val lastPoint = stroke.points.lastOrNull()
        if (lastPoint != null && isEffectivelySamePoint(lastPoint, point, samePointThreshold)) {
            return stroke
        }
        return stroke.copy(points = stroke.points + point)
    }

    fun finishStroke(stroke: Stroke): Stroke? = if (stroke.points.isEmpty()) null else stroke

    private fun isEffectivelySamePoint(
        first: StrokePoint,
        second: StrokePoint,
        samePointThreshold: Float
    ): Boolean =
        abs(first.x - second.x) < samePointThreshold &&
            abs(first.y - second.y) < samePointThreshold

    private companion object {
        const val DEFAULT_SAME_POINT_THRESHOLD = 0.5f
    }
}
