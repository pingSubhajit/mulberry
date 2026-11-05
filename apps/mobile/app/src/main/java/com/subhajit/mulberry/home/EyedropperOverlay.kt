package com.subhajit.mulberry.home

import android.graphics.Bitmap
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.core.ui.ColourNames
import com.subhajit.mulberry.drawing.engine.StrokeHitTester
import com.subhajit.mulberry.drawing.geometry.denormalizeToSurface
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasPaintItem
import com.subhajit.mulberry.drawing.model.CanvasPhotoElement
import com.subhajit.mulberry.photos.CanvasPhotoRenderer
import com.subhajit.mulberry.photos.CanvasPhotoStore
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.paintOrder
import com.subhajit.mulberry.drawing.text.rememberCanvasFontResolver
import com.subhajit.mulberry.stickers.StickerAssetStore
import com.subhajit.mulberry.stickers.StickerAssetVariant
import com.subhajit.mulberry.stickers.resolveStickerRenderSizePx
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun EyedropperOverlay(
    armed: Boolean,
    enabled: Boolean,
    canvasState: CanvasState,
    viewportTransform: CanvasViewportTransform,
    stickerAssetStore: StickerAssetStore,
    photoStore: CanvasPhotoStore,
    backgroundColorArgb: Long,
    onLiveColorArgbChanged: (Long?) -> Unit,
    onCommitColorArgb: (Long) -> Unit,
    onCanceled: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!armed || !enabled) return

    val density = LocalDensity.current
    val fontResolver = rememberCanvasFontResolver()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // The colour under the finger, shown in a loupe above it while picking.
    var loupe by remember { mutableStateOf<EyedropperLoupe?>(null) }
    val strokeHitTester = remember { StrokeHitTester() }
    val stickerBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    val textLayoutCache = remember { mutableMapOf<String, EyedropperCachedTextLayout>() }
    val baseTextSizePx = with(density) { 34.sp.toPx() }

    val latestCanvasState by rememberUpdatedState(canvasState)
    val latestViewportTransform by rememberUpdatedState(viewportTransform)
    val latestBackgroundColorArgb by rememberUpdatedState(backgroundColorArgb)
    val latestOnLiveChanged by rememberUpdatedState(onLiveColorArgbChanged)
    val latestOnCommit by rememberUpdatedState(onCommitColorArgb)
    val latestOnCanceled by rememberUpdatedState(onCanceled)

    LaunchedEffect(fontResolver) {
        textLayoutCache.clear()
    }

    fun getAlignment(align: CanvasTextAlign): Layout.Alignment = when (align) {
        CanvasTextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
        CanvasTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        CanvasTextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
    }

    fun getTextLayoutFor(element: CanvasTextElement): EyedropperCachedTextLayout {
        val wrapWidthPx = (element.boxWidth * canvasSize.width).roundToInt().coerceAtLeast(1)
        val cached = textLayoutCache[element.id]
        if (
            cached != null &&
            cached.text == element.text &&
            cached.font == element.font &&
            cached.alignment == element.alignment &&
            cached.wrapWidthPx == wrapWidthPx &&
            cached.textSizePx == baseTextSizePx
        ) {
            return cached
        }

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            typeface = fontResolver.typefaceFor(element.font)
            textSize = baseTextSizePx
        }

        val layout = StaticLayout.Builder
            .obtain(element.text, 0, element.text.length, paint, wrapWidthPx)
            .setAlignment(getAlignment(element.alignment))
            .setIncludePad(false)
            .build()

        return EyedropperCachedTextLayout(
            text = element.text,
            font = element.font,
            alignment = element.alignment,
            wrapWidthPx = wrapWidthPx,
            textSizePx = baseTextSizePx,
            layout = layout,
            tightBounds = computeTightTextBounds(layout)
        ).also { textLayoutCache[element.id] = it }
    }

    fun toContentOffset(point: Offset): Offset {
        val currentViewportTransform = latestViewportTransform
        val safeScale = currentViewportTransform.scale.coerceAtLeast(0.0001f)
        return (point - currentViewportTransform.offsetPx) / safeScale
    }

    fun sampleFromSticker(
        element: CanvasStickerElement,
        contentPointPx: Offset
    ): Long? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
        val centerPx = element.center.denormalize(canvasSize)
        val dx = contentPointPx.x - centerPx.x
        val dy = contentPointPx.y - centerPx.y
        val c = cos(element.rotationRad.toDouble()).toFloat()
        val s = sin(element.rotationRad.toDouble()).toFloat()
        val xRot = (c * dx) + (s * dy)
        val yRot = (-s * dx) + (c * dy)

        val key = "${element.packKey}:${element.packVersion}:${element.stickerId}:full"
        val bitmap = stickerBitmaps[key] ?: return null

        val maxSizePx = (element.scale.coerceIn(0.08f, 1.6f) * canvasSize.width.toFloat()).coerceAtLeast(1f)
        val renderSize = resolveStickerRenderSizePx(
            maxSizePx = maxSizePx,
            bitmapWidthPx = bitmap.width,
            bitmapHeightPx = bitmap.height
        )
        val halfW = renderSize.widthPx / 2f
        val halfH = renderSize.heightPx / 2f
        if (xRot !in -halfW..halfW || yRot !in -halfH..halfH) return null

        val u = ((xRot + halfW) / renderSize.widthPx).coerceIn(0f, 1f)
        val v = ((yRot + halfH) / renderSize.heightPx).coerceIn(0f, 1f)
        val px = (u * (bitmap.width - 1).coerceAtLeast(1)).roundToInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
        val py = (v * (bitmap.height - 1).coerceAtLeast(1)).roundToInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        val argb = bitmap.getPixel(px, py)
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0) return null
        return argb.toLong() and 0xFFFFFFFFL
    }

    fun sampleElementColorArgb(element: CanvasElement, contentPointPx: Offset): Long? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
        return when (element) {
            is CanvasTextElement -> {
                val pillPaddingPx = with(density) { 12.dp.toPx() }
                val centerPx = element.center.denormalize(canvasSize)
                val dx = contentPointPx.x - centerPx.x
                val dy = contentPointPx.y - centerPx.y
                val c = cos(element.rotationRad.toDouble()).toFloat()
                val s = sin(element.rotationRad.toDouble()).toFloat()
                val xRot = (c * dx) + (s * dy)
                val yRot = (-s * dx) + (c * dy)
                val cached = getTextLayoutFor(element)
                val xLocal = xRot / element.scale
                val yLocal = yRot / element.scale
                val layoutLeft = -cached.layout.width / 2f
                val layoutTop = -cached.layout.height / 2f
                val tight = cached.tightBounds
                var left = layoutLeft + tight.left
                var top = layoutTop + tight.top
                var right = layoutLeft + tight.right
                var bottom = layoutTop + tight.bottom
                if (element.backgroundPillEnabled) {
                    left -= pillPaddingPx
                    top -= pillPaddingPx
                    right += pillPaddingPx
                    bottom += pillPaddingPx
                }
                if (xLocal in left..right && yLocal in top..bottom) element.colorArgb else null
            }
            is CanvasStickerElement -> sampleFromSticker(element, contentPointPx)
            is CanvasPhotoElement -> CanvasPhotoRenderer.colorAt(
                x = contentPointPx.x,
                y = contentPointPx.y,
                photo = element,
                image = photoStore.cachedBitmap(element.photoId),
                cutout = element.cutoutPhotoId?.let(photoStore::cachedBitmap),
                canvasWidth = canvasSize.width.toFloat(),
                canvasHeight = canvasSize.height.toFloat()
            )?.let { argb -> argb.toLong() and 0xFFFFFFFFL }
        }
    }

    fun sampleStrokeColorArgb(stroke: Stroke, contentPointPx: Offset): Long? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
        val w = canvasSize.width.coerceAtLeast(1).toFloat()
        val h = canvasSize.height.coerceAtLeast(1).toFloat()
        val normalized = StrokePoint(
            x = (contentPointPx.x / w).coerceIn(0f, 1f),
            y = (contentPointPx.y / h).coerceIn(0f, 1f)
        )
        val hit = strokeHitTester.findStrokeHit(
            strokes = listOf(stroke.denormalizeToSurface(w, h)),
            point = normalized.denormalizeToSurface(w, h)
        ) ?: return null
        return hit.colorArgb
    }

    fun sampleAtScreenPoint(screenPointPx: Offset): Long {
        val content = toContentOffset(screenPointPx)
        val contentClamped = Offset(
            x = content.x.coerceIn(0f, canvasSize.width.toFloat().coerceAtLeast(0f)),
            y = content.y.coerceIn(0f, canvasSize.height.toFloat().coerceAtLeast(0f))
        )
        // The colour on top: strokes cover the text, and the text covers the stickers.
        return latestCanvasState.paintOrder().asReversed().firstNotNullOfOrNull { item ->
            when (item) {
                is CanvasPaintItem.StrokeItem -> sampleStrokeColorArgb(item.stroke, contentClamped)
                is CanvasPaintItem.Element -> sampleElementColorArgb(item.element, contentClamped)
            }
        } ?: latestBackgroundColorArgb
    }

    val stickerElements = remember(canvasState.elements) { canvasState.elements.filterIsInstance<CanvasStickerElement>() }
    LaunchedEffect(stickerElements) {
        stickerElements.forEach { element ->
            val key = "${element.packKey}:${element.packVersion}:${element.stickerId}:full"
            if (stickerBitmaps.containsKey(key)) return@forEach
            val bitmap = stickerAssetStore.loadStickerBitmap(
                packKey = element.packKey,
                packVersion = element.packVersion,
                stickerId = element.stickerId,
                variant = StickerAssetVariant.FULL
            ) ?: return@forEach
            stickerBitmaps[key] = bitmap
        }
    }

    LaunchedEffect(canvasState.elements) {
        val activeTextIds = canvasState.elements.filterIsInstance<CanvasTextElement>().map { it.id }.toSet()
        textLayoutCache.keys.toList().forEach { cachedId ->
            if (cachedId !in activeTextIds) textLayoutCache.remove(cachedId)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (canvasSize.width <= 0 || canvasSize.height <= 0) {
                        latestOnCanceled()
                        return@awaitEachGesture
                    }
                    var lastColorArgb: Long? = null
                    // As on iPhone, picking waits until the finger moves or lifts, so a finger
                    // held still can open the reactions instead.
                    val touchSlop = viewConfiguration.touchSlop
                    var sampling = false
                    fun sampleAt(position: Offset) {
                        val sampled = sampleAtScreenPoint(position)
                        lastColorArgb = sampled
                        latestOnLiveChanged(sampled)
                        loupe = EyedropperLoupe(position = position, colorArgb = sampled)
                    }
                    down.consume()

                    var canceled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val primary = event.changes.firstOrNull { it.id == down.id } ?: break
                        // Held for the reactions: no colour is picked.
                        if (primary.isConsumed) {
                            canceled = true
                            break
                        }
                        if (event.changes.count { it.pressed } > 1) {
                            canceled = true
                            event.changes.forEach { it.consume() }
                            break
                        }
                        if (!primary.pressed) {
                            // A tap picks the colour where the finger was.
                            if (!sampling) sampleAt(down.position)
                            primary.consume()
                            break
                        }
                        if (!sampling && (primary.position - down.position).getDistance() > touchSlop) {
                            sampling = true
                        }
                        if (sampling && primary.positionChanged()) sampleAt(primary.position)
                        primary.consume()
                    }
                    loupe = null

                    if (canceled) {
                        latestOnCanceled()
                        return@awaitEachGesture
                    }

                    val committed = lastColorArgb
                    if (committed != null) {
                        latestOnCommit(committed)
                    } else {
                        latestOnCanceled()
                    }
                }
            }
    ) {
        loupe?.let { current -> EyedropperLoupeBubble(current) }
    }
}

private data class EyedropperLoupe(
    val position: Offset,
    val colorArgb: Long
)

/** As on iOS: the picked colour in a white-ringed circle floating above the finger. */
@Composable
private fun EyedropperLoupeBubble(loupe: EyedropperLoupe) {
    val density = LocalDensity.current
    val radiusPx = with(density) { (EYEDROPPER_LOUPE_DIAMETER / 2).toPx() }
    val liftPx = with(density) { EYEDROPPER_LOUPE_LIFT.toPx() }
    val shadowColor = Color.Black.copy(alpha = 0.3f)
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (loupe.position.x - radiusPx).roundToInt(),
                    y = (loupe.position.y - liftPx - radiusPx).roundToInt()
                )
            }
            .size(EYEDROPPER_LOUPE_DIAMETER)
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
            .background(Color(loupe.colorArgb), CircleShape)
            .border(width = 3.dp, color = Color.White, shape = CircleShape)
            // TalkBack names the colour under the finger as it changes, as the palette names its colours.
            .semantics {
                contentDescription = ColourNames.name(loupe.colorArgb)
                liveRegion = LiveRegionMode.Polite
            }
    )
}

private val EYEDROPPER_LOUPE_DIAMETER = 44.dp
private val EYEDROPPER_LOUPE_LIFT = 64.dp

private data class EyedropperCachedTextLayout(
    val text: String,
    val font: CanvasTextFont,
    val alignment: CanvasTextAlign,
    val wrapWidthPx: Int,
    val textSizePx: Float,
    val layout: StaticLayout,
    val tightBounds: RectF
)

private fun StrokePoint.denormalize(canvasSize: IntSize): Offset =
    Offset(x = x * canvasSize.width.toFloat(), y = y * canvasSize.height.toFloat())

private fun computeTightTextBounds(layout: StaticLayout): RectF {
    val lineCount = layout.lineCount
    if (lineCount <= 0) return RectF(0f, 0f, 0f, 0f)

    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY

    for (i in 0 until lineCount) {
        val lineLeft = layout.getLineLeft(i)
        val lineRight = lineLeft + layout.getLineMax(i)
        val l = min(lineLeft, lineRight)
        val r = max(lineLeft, lineRight)
        if (l < left) left = l
        if (r > right) right = r
    }

    if (!left.isFinite() || !right.isFinite()) return RectF(0f, 0f, 0f, 0f)

    val top = layout.getLineTop(0).toFloat()
    val bottom = layout.getLineBottom(lineCount - 1).toFloat()
    return RectF(left, top, right, bottom)
}
