package com.subhajit.mulberry.drawing.data.local

import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint

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

fun CanvasTextElement.toEntity(): CanvasTextElementEntity = CanvasTextElementEntity(
    id = id,
    text = text,
    createdAt = createdAt,
    centerX = center.x,
    centerY = center.y,
    rotationRad = rotationRad,
    scale = scale,
    boxWidth = boxWidth,
    colorArgb = colorArgb,
    backgroundPillEnabled = backgroundPillEnabled,
    font = font,
    alignment = alignment
)

fun CanvasTextElementEntity.toDomain(): CanvasTextElement = CanvasTextElement(
    id = id,
    text = text,
    createdAt = createdAt,
    center = StrokePoint(x = centerX, y = centerY),
    rotationRad = rotationRad,
    scale = scale,
    boxWidth = boxWidth,
    colorArgb = colorArgb,
    backgroundPillEnabled = backgroundPillEnabled,
    font = font,
    alignment = alignment
)
