package com.subhajit.mulberry.drawing.engine

import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import javax.inject.Inject
import kotlin.math.sqrt

class StrokeHitTester @Inject constructor() {
    fun findStrokeHit(
        strokes: List<Stroke>,
        point: StrokePoint
    ): Stroke? = strokes.asReversed().firstOrNull { stroke ->
        isPointNearStroke(stroke, point)
    }

    private fun isPointNearStroke(
        stroke: Stroke,
        point: StrokePoint
    ): Boolean {
        val threshold = stroke.width / 2f + DrawingDefaults.STROKE_HIT_TOLERANCE
        val points = stroke.points
        if (points.isEmpty()) return false
        if (points.size == 1) {
            return distance(points.first(), point) <= threshold
        }

        return points.zipWithNext().any { (start, end) ->
            distanceToSegment(point, start, end) <= threshold
        }
    }

    private fun distanceToSegment(
        point: StrokePoint,
        start: StrokePoint,
        end: StrokePoint
    ): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        if (dx == 0f && dy == 0f) return distance(point, start)

        val projection = (
            ((point.x - start.x) * dx) + ((point.y - start.y) * dy)
            ) / ((dx * dx) + (dy * dy))
        val t = projection.coerceIn(0f, 1f)
        val projectedX = start.x + t * dx
        val projectedY = start.y + t * dy
        return distance(point, StrokePoint(projectedX, projectedY))
    }

    private fun distance(
        first: StrokePoint,
        second: StrokePoint
    ): Float {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return sqrt(dx * dx + dy * dy)
    }
}
