package com.subhajit.mulberry.drawing.render

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.nativeloader.NativeLoader
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as InkStroke
import androidx.ink.strokes.StrokeInput
import com.subhajit.mulberry.drawing.model.Stroke

object InkStrokeRenderer : StrokeBitmapRenderer, StrokeVisualRenderer {
    private val fallback = RoundStrokeRenderer
    private val identityMatrix = Matrix()

    @Volatile
    private var inkUnavailable = false

    private val renderer: CanvasStrokeRenderer? by lazy {
        runCatching {
            NativeLoader.load()
            CanvasStrokeRenderer.create(false)
        }.onFailure {
            inkUnavailable = true
        }.getOrNull()
    }

    override fun drawStroke(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.size < 2 || inkUnavailable) {
            fallback.drawStroke(canvas, stroke)
            return
        }

        val inkRenderer = renderer
        if (inkRenderer == null) {
            fallback.drawStroke(canvas, stroke)
            return
        }

        runCatching {
            inkRenderer.draw(canvas, stroke.toInkStroke(), identityMatrix)
        }.onFailure {
            inkUnavailable = true
            fallback.drawStroke(canvas, stroke)
        }
    }

    override fun DrawScope.drawStroke(stroke: Stroke) {
        if (stroke.points.size < 2 || inkUnavailable) {
            with(fallback) { drawStroke(stroke) }
            return
        }

        val inkRenderer = renderer
        if (inkRenderer == null) {
            with(fallback) { drawStroke(stroke) }
            return
        }

        drawIntoCanvas { composeCanvas ->
            runCatching {
                inkRenderer.draw(composeCanvas.nativeCanvas, stroke.toInkStroke(), identityMatrix)
            }.onFailure {
                inkUnavailable = true
                fallback.drawStroke(composeCanvas.nativeCanvas, stroke)
            }
        }
    }

    private fun Stroke.toInkStroke(): InkStroke {
        val brush = Brush.createWithColorIntArgb(
            StockBrushes.pressurePen(),
            colorArgb.toInt(),
            width,
            INK_EPSILON
        )
        val inputs = MutableStrokeInputBatch().apply {
            points.forEachIndexed { index, point ->
                add(
                    InputToolType.TOUCH,
                    point.x,
                    point.y,
                    index * INPUT_FRAME_MS,
                    StrokeInput.NO_STROKE_UNIT_LENGTH,
                    DEFAULT_PRESSURE,
                    StrokeInput.NO_TILT,
                    StrokeInput.NO_ORIENTATION
                )
            }
        }
        return InkStroke(brush, inputs)
    }

    private const val INPUT_FRAME_MS = 16L
    private const val DEFAULT_PRESSURE = 0.82f
    private const val INK_EPSILON = 0.1f
}
