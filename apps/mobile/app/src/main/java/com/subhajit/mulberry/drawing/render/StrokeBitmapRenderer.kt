package com.subhajit.mulberry.drawing.render

import android.graphics.Canvas
import com.subhajit.mulberry.drawing.model.Stroke

interface StrokeBitmapRenderer {
    fun drawStroke(canvas: Canvas, stroke: Stroke)

    fun drawStrokes(canvas: Canvas, strokes: List<Stroke>) {
        strokes.forEach { stroke -> drawStroke(canvas, stroke) }
    }
}
