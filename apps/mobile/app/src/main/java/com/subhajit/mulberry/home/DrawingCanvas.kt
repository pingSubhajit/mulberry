package com.subhajit.mulberry.home

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import com.subhajit.mulberry.core.ui.TestTags
import com.subhajit.mulberry.drawing.geometry.denormalizeToSurface
import com.subhajit.mulberry.drawing.geometry.normalizeToSurface
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.render.CanvasStrokeRenderMode
import com.subhajit.mulberry.drawing.render.committedStrokeBitmapRenderer
import com.subhajit.mulberry.drawing.render.committedStrokeVisualRenderer
import com.subhajit.mulberry.drawing.render.liveStrokeVisualRenderer
import com.subhajit.mulberry.wallpaper.canUseCommittedCanvasCache
import com.subhajit.mulberry.wallpaper.resolveDeviceRenderProfile
import com.subhajit.mulberry.drawing.model.Stroke as DrawingStroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DrawingCanvas(
    canvasState: CanvasState,
    activeTool: DrawingTool,
    onDrawStart: (StrokePoint) -> Unit,
    onDrawPoint: (StrokePoint) -> Unit,
    onDrawEnd: () -> Unit,
    onEraseTap: (StrokePoint) -> Unit,
    onCanvasSizeChanged: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    strokeRenderMode: CanvasStrokeRenderMode = CanvasStrokeRenderMode.Hybrid
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current.applicationContext
    val renderProfile = remember(context) { resolveDeviceRenderProfile(context) }
    var committedStrokeCache by remember { mutableStateOf(CommittedCanvasBitmapCache()) }

    DisposableEffect(Unit) {
        onDispose {
            committedStrokeCache.recycle()
        }
    }

    LaunchedEffect(canvasState.strokes, canvasSize, strokeRenderMode, renderProfile) {
        val currentStrokeIds = canvasState.strokes.map { stroke -> stroke.id }
        if (
            !canUseCommittedCanvasCache(
                width = canvasSize.width,
                height = canvasSize.height,
                profile = renderProfile
            ) ||
            canvasState.strokes.isEmpty()
        ) {
            committedStrokeCache = committedStrokeCache.replace(
                bitmap = null,
                strokeIds = currentStrokeIds,
                canvasSize = canvasSize,
                strokeRenderMode = strokeRenderMode
            )
            return@LaunchedEffect
        }

        if (committedStrokeCache.canAppend(canvasSize, strokeRenderMode, currentStrokeIds)) {
            committedStrokeCache.bitmap?.let { bitmap ->
                appendStrokeToCommittedCache(
                    bitmap = bitmap,
                    stroke = canvasState.strokes.last(),
                    strokeRenderMode = strokeRenderMode
                )
                committedStrokeCache = committedStrokeCache.copy(
                    strokeIds = currentStrokeIds,
                    version = committedStrokeCache.version + 1
                )
            }
            return@LaunchedEffect
        }

        val rebuiltBitmap = withContext(Dispatchers.Default) {
            buildCommittedStrokeCacheBitmap(canvasSize, canvasState.strokes, strokeRenderMode)
        }
        committedStrokeCache = committedStrokeCache.replace(
            bitmap = rebuiltBitmap,
            strokeIds = currentStrokeIds,
            canvasSize = canvasSize,
            strokeRenderMode = strokeRenderMode
        )
    }

    val gestureModifier = when (activeTool) {
        DrawingTool.DRAW -> Modifier.pointerInput(activeTool) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Claim the gesture immediately so parent horizontal pagers/tabs
                // don't treat this as a swipe-to-navigate.
                down.consume()
                onDrawStart(down.position.toStrokePoint(canvasSize))
                var pointer = down
                while (true) {
                    val event = awaitPointerEvent()
                    val dragChange = event.changes.firstOrNull { it.id == pointer.id }
                        ?: event.changes.firstOrNull()
                        ?: break
                    pointer = dragChange
                    if (!dragChange.pressed) break
                    if (dragChange.positionChanged()) {
                        onDrawPoint(dragChange.position.toStrokePoint(canvasSize))
                        dragChange.consume()
                    }
                }
                onDrawEnd()
            }
        }
        DrawingTool.ERASE -> Modifier.pointerInput(activeTool) {
            detectTapGestures { offset ->
                onEraseTap(offset.toStrokePoint(canvasSize))
            }
        }
        else -> Modifier
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
        val cacheVersion = committedStrokeCache.version
        val committedBitmap = committedStrokeCache.bitmap
        val currentStrokeIds = canvasState.strokes.map { stroke -> stroke.id }
        val overlayStrokeStartIndex = resolveCommittedCacheOverlayStartIndex(
            cacheHasBitmap = committedBitmap != null,
            cacheStrokeIds = committedStrokeCache.strokeIds,
            cacheCanvasSize = committedStrokeCache.canvasSize,
            cacheStrokeRenderMode = committedStrokeCache.strokeRenderMode,
            currentStrokeIds = currentStrokeIds,
            currentCanvasSize = canvasSize,
            currentStrokeRenderMode = strokeRenderMode
        )
        if (committedBitmap != null && overlayStrokeStartIndex != null) {
            cacheVersion
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(committedBitmap, 0f, 0f, null)
            }
            canvasState.strokes.drop(overlayStrokeStartIndex).forEach { stroke ->
                drawCommittedStroke(stroke.toRenderStroke(canvasSize), strokeRenderMode)
            }
        } else {
            canvasState.strokes.forEach { stroke ->
                drawCommittedStroke(stroke.toRenderStroke(canvasSize), strokeRenderMode)
            }
        }
        canvasState.remoteActiveStrokes.forEach { stroke ->
            drawLiveStroke(stroke.toRenderStroke(canvasSize), strokeRenderMode)
        }
        canvasState.activeStroke?.let { stroke ->
            drawLiveStroke(stroke.toRenderStroke(canvasSize), strokeRenderMode)
        }
    }
}

private fun buildCommittedStrokeCacheBitmap(
    canvasSize: IntSize,
    strokes: List<DrawingStroke>,
    strokeRenderMode: CanvasStrokeRenderMode
): Bitmap? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0 || strokes.isEmpty()) return null

    val bitmap = Bitmap.createBitmap(canvasSize.width, canvasSize.height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    strokeRenderMode.committedStrokeBitmapRenderer().drawStrokes(
        canvas,
        strokes.map { stroke -> stroke.toRenderStroke(canvasSize) }
    )
    return bitmap
}

private fun appendStrokeToCommittedCache(
    bitmap: Bitmap,
    stroke: DrawingStroke,
    strokeRenderMode: CanvasStrokeRenderMode
) {
    val canvas = AndroidCanvas(bitmap)
    strokeRenderMode.committedStrokeBitmapRenderer().drawStroke(
        canvas,
        stroke.toRenderStroke(IntSize(bitmap.width, bitmap.height))
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCommittedStroke(
    stroke: DrawingStroke,
    strokeRenderMode: CanvasStrokeRenderMode
) {
    with(strokeRenderMode.committedStrokeVisualRenderer()) { drawStroke(stroke) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLiveStroke(
    stroke: DrawingStroke,
    strokeRenderMode: CanvasStrokeRenderMode
) {
    with(strokeRenderMode.liveStrokeVisualRenderer()) { drawStroke(stroke) }
}

private fun Offset.toStrokePoint(canvasSize: IntSize): StrokePoint =
    StrokePoint(x = x, y = y).normalizeToSurface(canvasSize.width, canvasSize.height)

private fun DrawingStroke.toRenderStroke(canvasSize: IntSize): DrawingStroke =
    denormalizeToSurface(
        width = canvasSize.width.coerceAtLeast(1).toFloat(),
        height = canvasSize.height.coerceAtLeast(1).toFloat()
    )

internal fun resolveCommittedCacheOverlayStartIndex(
    cacheHasBitmap: Boolean,
    cacheStrokeIds: List<String>,
    cacheCanvasSize: IntSize,
    cacheStrokeRenderMode: CanvasStrokeRenderMode,
    currentStrokeIds: List<String>,
    currentCanvasSize: IntSize,
    currentStrokeRenderMode: CanvasStrokeRenderMode
): Int? {
    if (!cacheHasBitmap) return null
    if (cacheCanvasSize != currentCanvasSize) return null
    if (cacheStrokeRenderMode != currentStrokeRenderMode) return null
    if (currentStrokeIds.size < cacheStrokeIds.size) return null
    if (currentStrokeIds.take(cacheStrokeIds.size) != cacheStrokeIds) return null
    return cacheStrokeIds.size
}

private data class CommittedCanvasBitmapCache(
    val bitmap: Bitmap? = null,
    val strokeIds: List<String> = emptyList(),
    val canvasSize: IntSize = IntSize.Zero,
    val strokeRenderMode: CanvasStrokeRenderMode = CanvasStrokeRenderMode.Hybrid,
    val version: Long = 0L
) {
    fun canAppend(
        nextCanvasSize: IntSize,
        nextStrokeRenderMode: CanvasStrokeRenderMode,
        nextStrokeIds: List<String>
    ): Boolean {
        if (bitmap == null) return false
        if (canvasSize != nextCanvasSize || strokeRenderMode != nextStrokeRenderMode) return false
        if (nextStrokeIds.size != strokeIds.size + 1) return false
        return nextStrokeIds.take(strokeIds.size) == strokeIds
    }

    fun replace(
        bitmap: Bitmap?,
        strokeIds: List<String>,
        canvasSize: IntSize,
        strokeRenderMode: CanvasStrokeRenderMode
    ): CommittedCanvasBitmapCache {
        if (this.bitmap != null && this.bitmap != bitmap && !this.bitmap.isRecycled) {
            this.bitmap.recycle()
        }
        return copy(
            bitmap = bitmap,
            strokeIds = strokeIds,
            canvasSize = canvasSize,
            strokeRenderMode = strokeRenderMode,
            version = version + 1
        )
    }

    fun recycle() {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
