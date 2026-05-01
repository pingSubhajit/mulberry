package com.subhajit.mulberry.drawing.geometry

import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.sync.ServerCanvasOperation
import com.subhajit.mulberry.sync.SyncOperationPayload
import kotlin.math.min

const val NORMALIZED_COORDINATE_SPACE_VERSION = 1

private const val LEGACY_GEOMETRY_TOLERANCE = 0.001f

fun StrokePoint.normalizeToSurface(width: Int, height: Int): StrokePoint {
    val safeWidth = width.coerceAtLeast(1).toFloat()
    val safeHeight = height.coerceAtLeast(1).toFloat()
    return StrokePoint(
        x = (x / safeWidth).coerceIn(0f, 1f),
        y = (y / safeHeight).coerceIn(0f, 1f)
    )
}

fun StrokePoint.denormalizeToSurface(
    width: Float,
    height: Float,
    offsetX: Float = 0f,
    offsetY: Float = 0f
): StrokePoint = StrokePoint(
    x = offsetX + (x * width),
    y = offsetY + (y * height)
)

fun normalizeStrokeWidth(width: Float, surfaceWidth: Int, surfaceHeight: Int): Float {
    val referenceDimension = min(
        surfaceWidth.coerceAtLeast(1),
        surfaceHeight.coerceAtLeast(1)
    ).toFloat()
    return (width / referenceDimension).coerceAtLeast(0f)
}

fun denormalizeStrokeWidth(
    normalizedWidth: Float,
    surfaceWidth: Float,
    surfaceHeight: Float
): Float {
    val referenceDimension = min(surfaceWidth.coerceAtLeast(1f), surfaceHeight.coerceAtLeast(1f))
    return normalizedWidth * referenceDimension
}

fun Stroke.denormalizeToSurface(
    width: Float,
    height: Float,
    offsetX: Float = 0f,
    offsetY: Float = 0f
): Stroke = copy(
    width = denormalizeStrokeWidth(this.width, width, height),
    points = points.map { point -> point.denormalizeToSurface(width, height, offsetX, offsetY) }
)

fun Stroke.hasLegacyGeometry(): Boolean =
    points.any { point ->
        point.x < -LEGACY_GEOMETRY_TOLERANCE ||
            point.x > 1f + LEGACY_GEOMETRY_TOLERANCE ||
            point.y < -LEGACY_GEOMETRY_TOLERANCE ||
            point.y > 1f + LEGACY_GEOMETRY_TOLERANCE
    }

fun List<Stroke>.containsLegacyGeometry(): Boolean = any { stroke -> stroke.hasLegacyGeometry() }

fun SyncOperationPayload.hasLegacyGeometry(): Boolean = when (this) {
    is SyncOperationPayload.AddStroke -> {
        firstPoint.x < -LEGACY_GEOMETRY_TOLERANCE ||
            firstPoint.x > 1f + LEGACY_GEOMETRY_TOLERANCE ||
            firstPoint.y < -LEGACY_GEOMETRY_TOLERANCE ||
            firstPoint.y > 1f + LEGACY_GEOMETRY_TOLERANCE
    }
    is SyncOperationPayload.AppendPoints -> points.any { point ->
        point.x < -LEGACY_GEOMETRY_TOLERANCE ||
            point.x > 1f + LEGACY_GEOMETRY_TOLERANCE ||
            point.y < -LEGACY_GEOMETRY_TOLERANCE ||
            point.y > 1f + LEGACY_GEOMETRY_TOLERANCE
    }
    SyncOperationPayload.FinishStroke,
    SyncOperationPayload.DeleteStroke,
    SyncOperationPayload.ClearCanvas,
    is SyncOperationPayload.AddTextElement,
    is SyncOperationPayload.UpdateTextElement,
    SyncOperationPayload.DeleteTextElement -> false
}

fun ServerCanvasOperation.hasLegacyGeometry(): Boolean = payload.hasLegacyGeometry()
