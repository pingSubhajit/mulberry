package com.subhajit.mulberry.home

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.render.DryBrushStrokeRenderer
import com.subhajit.mulberry.drawing.model.Stroke as DrawingStroke
import com.subhajit.mulberry.drawing.model.StrokePoint

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
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val committedStrokeCache = remember(canvasState.strokes, canvasSize) {
        buildCommittedStrokeCache(canvasSize, canvasState.strokes)
    }
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
                canvasSize = size
                onCanvasSizeChanged(size.width, size.height)
            }
            .testTag(TestTags.DRAWING_CANVAS)
            .then(gestureModifier)
    ) {
        committedStrokeCache?.let { drawImage(it) } ?: canvasState.strokes.forEach { drawStroke(it) }
        canvasState.remoteActiveStrokes.forEach { drawStroke(it) }
        canvasState.activeStroke?.let { drawStroke(it) }
    }
}

private fun buildCommittedStrokeCache(
    canvasSize: IntSize,
    strokes: List<DrawingStroke>
): ImageBitmap? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || strokes.isEmpty()) return null

    val bitmap = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    DryBrushStrokeRenderer.drawStrokes(canvas, strokes)
    return bitmap.asImageBitmap()
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStroke(stroke: DrawingStroke) {
    with(DryBrushStrokeRenderer) { drawStroke(stroke) }
}

private fun Offset.toStrokePoint(): StrokePoint = StrokePoint(x = x, y = y)
