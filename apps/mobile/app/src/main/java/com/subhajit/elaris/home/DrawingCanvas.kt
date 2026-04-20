package com.subhajit.elaris.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import com.subhajit.elaris.core.ui.TestTags
import com.subhajit.elaris.drawing.model.CanvasState
import com.subhajit.elaris.drawing.model.DrawingTool
import com.subhajit.elaris.drawing.model.Stroke as DrawingStroke
import com.subhajit.elaris.drawing.model.StrokePoint

@Composable
fun DrawingCanvas(
    canvasState: CanvasState,
    activeTool: DrawingTool,
    onDrawStart: (StrokePoint) -> Unit,
    onDrawPoint: (StrokePoint) -> Unit,
    onDrawEnd: () -> Unit,
    onEraseTap: (StrokePoint) -> Unit,
    onCanvasSizeChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val gestureModifier = if (activeTool == DrawingTool.DRAW) {
        Modifier.pointerInput(activeTool) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                onDrawStart(down.position.toStrokePoint())
                var pointer = down
                while (true) {
                    val event = awaitPointerEvent()
                    val dragChange = event.changes.firstOrNull { it.id == pointer.id }
                        ?: event.changes.firstOrNull()
                        ?: break
                    pointer = dragChange
                    if (!dragChange.pressed) break
                    if (dragChange.positionChanged()) {
                        onDrawPoint(dragChange.position.toStrokePoint())
                        dragChange.consume()
                    }
                }
                onDrawEnd()
            }
        }
    } else {
        Modifier.pointerInput(activeTool) {
            detectTapGestures { offset ->
                onEraseTap(offset.toStrokePoint())
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                onCanvasSizeChanged(size.width, size.height)
            }
            .testTag(TestTags.DRAWING_CANVAS)
            .then(gestureModifier)
    ) {
        canvasState.strokes.forEach { drawStroke(it) }
        canvasState.activeStroke?.let { drawStroke(it) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(stroke: DrawingStroke) {
    if (stroke.points.isEmpty()) return
    val color = Color(stroke.colorArgb)
    if (stroke.points.size == 1) {
        drawCircle(
            color = color,
            radius = stroke.width / 2f,
            center = Offset(stroke.points.first().x, stroke.points.first().y)
        )
        return
    }

    val path = Path().apply {
        moveTo(stroke.points.first().x, stroke.points.first().y)
        stroke.points.drop(1).forEach { point ->
            lineTo(point.x, point.y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = stroke.width,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun Offset.toStrokePoint(): StrokePoint = StrokePoint(x = x, y = y)
