package com.subhajit.mulberry.drawing.render

enum class CanvasStrokeRenderMode {
    Hybrid,
    DryBrushOnly,
    RoundStrokeOnly;

    companion object {
        fun fromRaw(raw: String?): CanvasStrokeRenderMode {
            return when (raw?.trim()?.lowercase()) {
                "dry", "dry_brush", "dry-brush", "dry_brush_only", "dry-brush-only" ->
                    DryBrushOnly
                "round", "round_stroke", "round-stroke", "round_stroke_only", "round-stroke-only" ->
                    RoundStrokeOnly
                else -> Hybrid
            }
        }
    }
}

internal fun CanvasStrokeRenderMode.committedStrokeBitmapRenderer(): StrokeBitmapRenderer =
    when (this) {
        CanvasStrokeRenderMode.RoundStrokeOnly -> RoundStrokeRenderer
        CanvasStrokeRenderMode.Hybrid,
        CanvasStrokeRenderMode.DryBrushOnly -> DryBrushStrokeRenderer
    }

internal fun CanvasStrokeRenderMode.committedStrokeVisualRenderer(): StrokeVisualRenderer =
    when (this) {
        CanvasStrokeRenderMode.RoundStrokeOnly -> RoundStrokeRenderer
        CanvasStrokeRenderMode.Hybrid,
        CanvasStrokeRenderMode.DryBrushOnly -> DryBrushStrokeRenderer
    }

internal fun CanvasStrokeRenderMode.liveStrokeVisualRenderer(): StrokeVisualRenderer =
    when (this) {
        CanvasStrokeRenderMode.DryBrushOnly -> DryBrushStrokeRenderer
        CanvasStrokeRenderMode.Hybrid,
        CanvasStrokeRenderMode.RoundStrokeOnly -> RoundStrokeRenderer
    }
