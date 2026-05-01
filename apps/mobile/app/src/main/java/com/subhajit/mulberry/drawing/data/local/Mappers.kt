package com.subhajit.mulberry.drawing.data.local

import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint

fun Stroke.toEntity(canvasKey: String): StrokeEntity = StrokeEntity(
    key = strokeKey(canvasKey, id),
    canvasKey = canvasKey,
    strokeId = id,
    colorArgb = colorArgb,
    width = width,
    createdAt = createdAt
)

fun Stroke.toPointEntities(canvasKey: String): List<StrokePointEntity> = points.mapIndexed { index, point ->
    StrokePointEntity(
        strokeKey = strokeKey(canvasKey, id),
        pointIndex = index,
        x = point.x,
        y = point.y
    )
}

fun StrokeWithPoints.toDomain(): Stroke = Stroke(
    id = stroke.strokeId,
    colorArgb = stroke.colorArgb,
    width = stroke.width,
    createdAt = stroke.createdAt,
    points = points
        .sortedBy { it.pointIndex }
        .map { StrokePoint(x = it.x, y = it.y) }
)
