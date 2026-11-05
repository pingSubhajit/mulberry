package com.subhajit.mulberry.drawing.engine

import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.sqrt

class StrokeHitTester @Inject constructor() {
    fun findStrokeHit(
        strokes: List<Stroke>,
        point: StrokePoint
    ): Stroke? = strokes.asReversed().firstOrNull { stroke ->
        isSegmentNearStroke(stroke, point, point)
    }

    /**
     * Every stroke the eraser crosses moving from [from] to [to], newest first. The whole segment
     * is tested, so a fast drag between two touch samples still catches the strokes in between.
     */
    fun findStrokesAlongSegment(
        strokes: List<Stroke>,
        from: StrokePoint,
        to: StrokePoint
    ): List<Stroke> = strokes.asReversed().filter { stroke ->
        isSegmentNearStroke(stroke, from, to)
    }

    private fun isSegmentNearStroke(
        stroke: Stroke,
        from: StrokePoint,
        to: StrokePoint
    ): Boolean {
        val threshold = stroke.width / 2f + DrawingDefaults.STROKE_HIT_TOLERANCE
        val points = stroke.points
        if (points.isEmpty()) return false
        if (points.size == 1) {
            return distanceToSegment(points.first(), from, to) <= threshold
        }

        return points.zipWithNext().any { (start, end) ->
            distanceBetweenSegments(from, to, start, end) <= threshold
        }
    }

    private fun distanceBetweenSegments(
        firstStart: StrokePoint,
        firstEnd: StrokePoint,
        secondStart: StrokePoint,
        secondEnd: StrokePoint
    ): Float {
        if (segmentsIntersect(firstStart, firstEnd, secondStart, secondEnd)) return 0f
        return min(
            min(
                distanceToSegment(firstStart, secondStart, secondEnd),
                distanceToSegment(firstEnd, secondStart, secondEnd)
            ),
            min(
                distanceToSegment(secondStart, firstStart, firstEnd),
                distanceToSegment(secondEnd, firstStart, firstEnd)
            )
        )
    }

    private fun segmentsIntersect(
        firstStart: StrokePoint,
        firstEnd: StrokePoint,
        secondStart: StrokePoint,
        secondEnd: StrokePoint
    ): Boolean {
        val d1 = cross(secondStart, secondEnd, firstStart)
        val d2 = cross(secondStart, secondEnd, firstEnd)
        val d3 = cross(firstStart, firstEnd, secondStart)
        val d4 = cross(firstStart, firstEnd, secondEnd)
        // Touching or collinear segments are left to the distance checks, which return zero.
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
            ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    private fun cross(origin: StrokePoint, a: StrokePoint, b: StrokePoint): Float =
        (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)

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
