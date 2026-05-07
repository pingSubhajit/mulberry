package com.subhajit.mulberry.drawing.render

enum class CanvasStrokeRenderMode {
    DryBrush,
    RoundStroke;

    companion object {
        fun fromRaw(raw: String?): CanvasStrokeRenderMode {
            return when (raw?.trim()?.lowercase()) {
                "dry", "dry_brush", "dry-brush", "dry_brush_only", "dry-brush-only", "dry_brush_only_strokes" ->
                    DryBrush
                "round", "round_stroke", "round-stroke", "round_stroke_only", "round-stroke-only", "round_stroke_only_strokes" ->
                    RoundStroke
                // Legacy build-time mode. Hybrid no longer exists; default to DryBrush to match current product default.
                "hybrid" -> DryBrush
                else -> DryBrush
            }
        }
    }
}

internal fun CanvasStrokeRenderMode.committedStrokeBitmapRenderer(): StrokeBitmapRenderer =
    when (this) {
        CanvasStrokeRenderMode.RoundStroke -> RoundStrokeRenderer
        CanvasStrokeRenderMode.DryBrush -> DryBrushStrokeRenderer
    }

internal fun CanvasStrokeRenderMode.committedStrokeVisualRenderer(): StrokeVisualRenderer =
    when (this) {
        CanvasStrokeRenderMode.RoundStroke -> RoundStrokeRenderer
        CanvasStrokeRenderMode.DryBrush -> DryBrushStrokeRenderer
    }

internal fun CanvasStrokeRenderMode.liveStrokeVisualRenderer(): StrokeVisualRenderer =
    when (this) {
        CanvasStrokeRenderMode.DryBrush -> DryBrushStrokeRenderer
        CanvasStrokeRenderMode.RoundStroke -> RoundStrokeRenderer
    }
