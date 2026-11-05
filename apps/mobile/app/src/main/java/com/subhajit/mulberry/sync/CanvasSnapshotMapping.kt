package com.subhajit.mulberry.sync

import android.util.Log
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasPhotoElement
import com.subhajit.mulberry.drawing.model.CanvasPhotoStyle
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.network.CanvasSnapshotPayload

private const val TAG = "CanvasSnapshotMapping"

internal fun CanvasSnapshotPayload.toDomainStrokes(): List<Stroke> =
    strokes.map { stroke ->
        Stroke(
            id = stroke.id,
            colorArgb = stroke.colorArgb,
            width = stroke.width,
            createdAt = stroke.createdAt,
            points = stroke.points.map { point ->
                StrokePoint(x = point.x, y = point.y)
            }
        )
    }

/**
 * The elements this build can draw. Kinds it doesn't know, from a newer app, are skipped rather
 * than failing the snapshot.
 */
internal fun CanvasSnapshotPayload.toDomainElements(): List<CanvasElement> {
    val unified = elements.orEmpty()
    if (unified.isNotEmpty()) {
        return unified.mapNotNull { element ->
            when (element.kind) {
                "TEXT" -> {
                    CanvasTextElement(
                        id = element.id,
                        text = element.text.orEmpty(),
                        createdAt = element.createdAt,
                        center = StrokePoint(x = element.center.x, y = element.center.y),
                        rotationRad = element.rotationRad,
                        scale = element.scale,
                        boxWidth = element.boxWidth ?: 0.7f,
                        colorArgb = element.colorArgb ?: 0xff111111,
                        backgroundPillEnabled = element.backgroundPillEnabled ?: false,
                        font = runCatching { CanvasTextFont.valueOf(element.font ?: "POPPINS") }
                            .getOrElse { CanvasTextFont.POPPINS },
                        alignment = runCatching { CanvasTextAlign.valueOf(element.alignment ?: "CENTER") }
                            .getOrElse { CanvasTextAlign.CENTER }
                    )
                }
                "STICKER" -> {
                    val packKey = element.packKey?.trim().orEmpty()
                    val stickerId = element.stickerId?.trim().orEmpty()
                    val packVersion = element.packVersion ?: 0
                    if (packKey.isBlank() || stickerId.isBlank() || packVersion <= 0) return@mapNotNull null
                    CanvasStickerElement(
                        id = element.id,
                        createdAt = element.createdAt,
                        center = StrokePoint(x = element.center.x, y = element.center.y),
                        rotationRad = element.rotationRad,
                        scale = element.scale,
                        packKey = packKey,
                        packVersion = packVersion,
                        stickerId = stickerId
                    )
                }
                "PHOTO" -> {
                    val photoId = element.photoId?.trim().orEmpty()
                    val aspectRatio = element.aspectRatio ?: 0f
                    if (photoId.isBlank() || aspectRatio <= 0f || !aspectRatio.isFinite()) return@mapNotNull null
                    CanvasPhotoElement(
                        id = element.id,
                        createdAt = element.createdAt,
                        center = StrokePoint(x = element.center.x, y = element.center.y),
                        rotationRad = element.rotationRad,
                        scale = element.scale,
                        photoId = photoId,
                        cutoutPhotoId = element.cutoutPhotoId?.trim()?.takeIf { it.isNotEmpty() },
                        aspectRatio = aspectRatio,
                        style = CanvasPhotoStyle.fromWire(element.style)
                    )
                }
                else -> {
                    Log.w(TAG, "Skipping unsupported canvas element kind=${element.kind} id=${element.id}")
                    null
                }
            }
        }
    }

    // Back-compat: older snapshots only include textElements.
    return textElements.map { element ->
        CanvasTextElement(
            id = element.id,
            text = element.text,
            createdAt = element.createdAt,
            center = StrokePoint(x = element.center.x, y = element.center.y),
            rotationRad = element.rotationRad,
            scale = element.scale,
            boxWidth = element.boxWidth,
            colorArgb = element.colorArgb,
            backgroundPillEnabled = element.backgroundPillEnabled,
            font = runCatching { CanvasTextFont.valueOf(element.font) }.getOrElse { CanvasTextFont.POPPINS },
            alignment = runCatching { CanvasTextAlign.valueOf(element.alignment) }.getOrElse { CanvasTextAlign.CENTER }
        )
    }
}
