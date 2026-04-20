package com.subhajit.elaris.drawing.data.local

import com.subhajit.elaris.drawing.model.Stroke
import com.subhajit.elaris.drawing.model.StrokePoint

fun Stroke.toEntity(): StrokeEntity = StrokeEntity(
    id = id,
    colorArgb = colorArgb,
    width = width,
    createdAt = createdAt
)

fun Stroke.toPointEntities(): List<StrokePointEntity> = points.mapIndexed { index, point ->
    StrokePointEntity(
        strokeId = id,
        pointIndex = index,
        x = point.x,
        y = point.y
    )
}

fun StrokeWithPoints.toDomain(): Stroke = Stroke(
    id = stroke.id,
    colorArgb = stroke.colorArgb,
    width = stroke.width,
    createdAt = stroke.createdAt,
    points = points
        .sortedBy { it.pointIndex }
        .map { StrokePoint(x = it.x, y = it.y) }
)
