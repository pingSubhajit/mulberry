package com.subhajit.mulberry.drawing.render

import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint

fun Stroke.transformed(scale: Float, offsetX: Float, offsetY: Float): Stroke = copy(
    width = width * scale,
    points = points.map { point ->
        StrokePoint(
            x = offsetX + (point.x * scale),
            y = offsetY + (point.y * scale)
        )
    }
)
