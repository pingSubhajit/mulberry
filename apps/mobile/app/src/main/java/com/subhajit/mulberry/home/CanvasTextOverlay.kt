package com.subhajit.mulberry.home

import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import com.subhajit.mulberry.R
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.CanvasPhotoElement
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.photos.CanvasPhotoRenderer
import com.subhajit.mulberry.photos.CanvasPhotoStore
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.inPaintOrder
import com.subhajit.mulberry.drawing.model.topmostElement
import com.subhajit.mulberry.drawing.engine.StrokeHitTester
import com.subhajit.mulberry.drawing.geometry.denormalizeToSurface
import com.subhajit.mulberry.drawing.text.canvasDisplayName
import com.subhajit.mulberry.drawing.text.rememberCanvasFontResolver
import com.subhajit.mulberry.stickers.StickerAssetStore
import com.subhajit.mulberry.stickers.StickerAssetVariant
import com.subhajit.mulberry.stickers.resolveStickerRenderSizePx
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import java.util.UUID
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.subhajit.mulberry.core.ui.ColourNames
import com.subhajit.mulberry.core.ui.HapticOnChange
import com.subhajit.mulberry.core.ui.MulberryHaptic
import com.subhajit.mulberry.core.ui.rememberMulberryHaptics
import com.subhajit.mulberry.ui.animation.rememberSystemAnimationsEnabled
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.math.PI
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/** Sticker artwork, whatever the sticker's place on the canvas. */
private data class StickerArtwork(val packKey: String, val packVersion: Int, val stickerId: String) {
    val key: String get() = "$packKey:$packVersion:$stickerId:full"
}

private fun CanvasStickerElement.artwork() = StickerArtwork(packKey, packVersion, stickerId)

// Artwork that only needs decoding fades in, with no placeholder first. A placeholder waits this
// long, so it only appears while a download keeps the canvas waiting.
private const val STICKER_PLACEHOLDER_DELAY_MS = 400L
private const val STICKER_REVEAL_MS = 180

private data class CachedTextLayout(
    val text: String,
    val font: CanvasTextFont,
    val alignment: CanvasTextAlign,
    val wrapWidthPx: Int,
    val textSizePx: Float,
    val layout: StaticLayout,
    val paint: TextPaint,
    val tightBounds: RectF
)

data class CanvasTextEditorSession(
    val element: CanvasTextElement,
    val isNew: Boolean
)

@Composable
fun CanvasTextOverlay(
    elements: List<CanvasElement>,
    // Strokes cover every element, so an eraser tap on one erases the stroke, not what's under it.
    strokes: List<Stroke>,
    activeTool: DrawingTool,
    palette: List<Long>,
    selectedColorArgb: Long,
    showElementBounds: Boolean = false,
    stickerAssetStore: StickerAssetStore,
    viewportTransform: CanvasViewportTransform,
    onEraseTap: (StrokePoint) -> Unit,
    onEraseDrag: (StrokePoint, StrokePoint) -> Unit,
    onAddTextElement: (CanvasTextElement) -> Unit,
    onUpdateTextElement: (CanvasTextElement) -> Unit,
    onDeleteTextElement: (String) -> Unit,
    onAddStickerElement: (CanvasStickerElement) -> Unit,
    onUpdateStickerElement: (CanvasStickerElement) -> Unit,
    onDeleteStickerElement: (String) -> Unit,
    onUpdatePhotoElement: (CanvasPhotoElement) -> Unit,
    onDeletePhotoElement: (String) -> Unit,
    photoStore: CanvasPhotoStore,
    onRequestTextEdit: (CanvasTextEditorSession) -> Unit,
    onRequestStickerEdit: (CanvasStickerEditorSession) -> Unit,
    onRequestNewStickerAt: (StrokePoint) -> Unit,
    onRequestPhotoEdit: (CanvasPhotoElement) -> Unit,
    onRequestNewPhoto: () -> Unit,
    allowCreateOnEmptyTap: Boolean,
    onEmptyTapCreationHandled: () -> Unit,
    isEditorOpen: Boolean,
    modifier: Modifier = Modifier,
    // The element an editor has open, which shows in the editor instead.
    hiddenElementId: String? = null
) {
    val density = LocalDensity.current
    val fontResolver = rememberCanvasFontResolver()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var liveTransformPreview by remember { mutableStateOf<CanvasElement?>(null) }
    val stickerBitmaps = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }
    // Artwork decoded while the canvas is open fades in; artwork already in memory draws at once.
    val stickerReveals = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    // Artwork still downloading after a moment shows a placeholder: true while it pulses, false
    // once the download has failed and it rests.
    val stickerPlaceholders = remember { mutableStateMapOf<String, Boolean>() }

    fun stickerBitmapFor(element: CanvasStickerElement): android.graphics.Bitmap? =
        stickerBitmaps[element.artwork().key] ?: stickerAssetStore.cachedStickerBitmap(
            packKey = element.packKey,
            packVersion = element.packVersion,
            stickerId = element.stickerId,
            variant = StickerAssetVariant.FULL
        )

    // The placeholder's ink stands out from the canvas: dark on a light canvas, light on a dark one.
    val canvasSurface = MaterialTheme.mulberryAppColors.softSurface
    val placeholderInkArgb = remember(canvasSurface) {
        (if (canvasSurface.luminance() > 0.5f) Color.Black else Color.White).toArgb()
    }
    val animationsEnabled = rememberSystemAnimationsEnabled()
    val haptics = rememberMulberryHaptics()
    // Pulses only while a placeholder is waiting on a download, so the canvas isn't redrawn otherwise.
    val placeholderPulse = if (animationsEnabled && stickerPlaceholders.values.any { it }) {
        rememberInfiniteTransition(label = "stickerPlaceholder").animateFloat(
            initialValue = 0.08f,
            targetValue = 0.18f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "stickerPlaceholderPulse"
        )
    } else {
        null
    }
    val textLayoutCache = remember { mutableMapOf<String, CachedTextLayout>() }
    LaunchedEffect(fontResolver) {
        textLayoutCache.clear()
    }
    val latestViewportTransform by rememberUpdatedState(viewportTransform)
    // The gesture handler outlives a recomposition, so it reads the latest value.
    val latestAllowCreateOnEmptyTap by rememberUpdatedState(allowCreateOnEmptyTap)

    LaunchedEffect(activeTool) {
        if (activeTool != DrawingTool.TEXT && activeTool != DrawingTool.STICKER) {
            liveTransformPreview = null
        }
    }

    LaunchedEffect(isEditorOpen) {
        if (!isEditorOpen) {
            liveTransformPreview = null
        }
    }

    val baseTextSizePx = with(density) { 34.sp.toPx() }
    var isTransformInProgress by remember { mutableStateOf(false) }
    val pillBackgroundPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    val stickerBitmapPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; isFilterBitmap = true } }
    val stickerPlaceholderPaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    val textBoundsPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            color = android.graphics.Color.MAGENTA
        }
    }
    val textPillBoundsPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            color = android.graphics.Color.CYAN
        }
    }
    val stickerBoundsPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            color = android.graphics.Color.GREEN
        }
    }

    fun getTextLayoutFor(element: CanvasTextElement): CachedTextLayout {
        val wrapWidthPx = (element.boxWidth * canvasSize.width).toInt().coerceAtLeast(1)
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

        val typeface = fontResolver.typefaceFor(element.font)

        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = baseTextSizePx
            this.typeface = typeface
            // Color is set at draw-time (depends on background pill and element color).
            color = 0xFFFFFFFF.toInt()
        }

        val alignment = when (element.alignment) {
            CanvasTextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            CanvasTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            CanvasTextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }

        val layout = StaticLayout.Builder.obtain(element.text, 0, element.text.length, paint, wrapWidthPx)
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()

        return CachedTextLayout(
            text = element.text,
            font = element.font,
            alignment = element.alignment,
            wrapWidthPx = wrapWidthPx,
            textSizePx = baseTextSizePx,
            layout = layout,
            paint = paint,
            tightBounds = computeTightTextBounds(layout)
        ).also { textLayoutCache[element.id] = it }
    }

    // A small photo is still easy to aim at, as on iOS.
    val photoMinTargetPx = with(density) { 44.dp.toPx() }

    fun hitTestAtContentPosition(
        elementsForHitTest: List<CanvasElement>,
        pointPx: Offset
    ): String? {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
        val pillPaddingPx = with(density) { 12.dp.toPx() }
        return elementsForHitTest.topmostElement { element ->
            val centerPx = element.center.denormalize(canvasSize)
            val dx = pointPx.x - centerPx.x
            val dy = pointPx.y - centerPx.y
            val c = cos(element.rotationRad.toDouble()).toFloat()
            val s = sin(element.rotationRad.toDouble()).toFloat()
            val xRot = (c * dx) + (s * dy)
            val yRot = (-s * dx) + (c * dy)

            when (element) {
                is CanvasTextElement -> {
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
                    xLocal in left..right && yLocal in top..bottom
                }
                is CanvasStickerElement -> {
                    val bitmap = stickerBitmapFor(element)
                    val maxSizePx = (element.scale.coerceIn(0.08f, 1.6f) * canvasSize.width.toFloat()).coerceAtLeast(1f)
                    val renderSize = if (bitmap != null) {
                        resolveStickerRenderSizePx(
                            maxSizePx = maxSizePx,
                            bitmapWidthPx = bitmap.width,
                            bitmapHeightPx = bitmap.height
                        )
                    } else {
                        com.subhajit.mulberry.stickers.StickerRenderSizePx(
                            widthPx = maxSizePx,
                            heightPx = maxSizePx
                        )
                    }
                    val halfW = renderSize.widthPx / 2f
                    val halfH = renderSize.heightPx / 2f
                    xRot in -halfW..halfW && yRot in -halfH..halfH
                }
                is CanvasPhotoElement -> CanvasPhotoRenderer.contains(
                    x = pointPx.x,
                    y = pointPx.y,
                    photo = element,
                    cutout = element.cutoutPhotoId?.let(photoStore::cachedBitmap),
                    canvasWidth = canvasSize.width.toFloat(),
                    canvasHeight = canvasSize.height.toFloat(),
                    minTarget = photoMinTargetPx
                )
            }
        }?.id
    }

    val latestStrokes by rememberUpdatedState(strokes)
    val strokeHitTester = remember { StrokeHitTester() }

    fun isStrokeAt(contentPointPx: Offset): Boolean {
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return false
        val width = canvasSize.width.toFloat()
        val height = canvasSize.height.toFloat()
        return strokeHitTester.findStrokeHit(
            strokes = latestStrokes.map { stroke -> stroke.denormalizeToSurface(width, height) },
            point = StrokePoint(x = contentPointPx.x, y = contentPointPx.y)
        ) != null
    }

    val elementsInPaintOrder = remember(elements) { elements.inPaintOrder() }
    val stickerElements = remember(elements) { elements.filterIsInstance<CanvasStickerElement>() }
    // Keyed on the artwork rather than the elements, so moving a sticker doesn't restart its load.
    val stickerArtwork = remember(stickerElements) { stickerElements.map { it.artwork() }.distinct() }
    LaunchedEffect(stickerArtwork) {
        stickerArtwork.forEach { artwork ->
            val key = artwork.key
            if (stickerBitmaps.containsKey(key) || stickerReveals.containsKey(key)) return@forEach
            stickerAssetStore.cachedStickerBitmap(
                packKey = artwork.packKey,
                packVersion = artwork.packVersion,
                stickerId = artwork.stickerId,
                variant = StickerAssetVariant.FULL
            )?.let { cached ->
                stickerBitmaps[key] = cached
                return@forEach
            }
            launch {
                val onDevice = stickerAssetStore.hasStickerAsset(
                    packKey = artwork.packKey,
                    packVersion = artwork.packVersion,
                    stickerId = artwork.stickerId,
                    variant = StickerAssetVariant.FULL
                )
                val placeholder = if (onDevice) {
                    null
                } else {
                    launch {
                        delay(STICKER_PLACEHOLDER_DELAY_MS)
                        stickerPlaceholders[key] = true
                    }
                }
                val bitmap = runCatching {
                    stickerAssetStore.loadStickerBitmap(
                        packKey = artwork.packKey,
                        packVersion = artwork.packVersion,
                        stickerId = artwork.stickerId,
                        variant = StickerAssetVariant.FULL
                    )
                }.getOrNull()
                placeholder?.cancel()
                if (bitmap == null) {
                    // Offline, or the artwork is gone: the placeholder rests in the sticker's place.
                    stickerPlaceholders[key] = false
                    return@launch
                }
                // The artwork fades in as any placeholder fades out.
                val reveal = Animatable(0f)
                stickerReveals[key] = reveal
                stickerBitmaps[key] = bitmap
                try {
                    reveal.animateTo(1f, tween(durationMillis = STICKER_REVEAL_MS, easing = FastOutSlowInEasing))
                } finally {
                    stickerReveals.remove(key)
                    stickerPlaceholders.remove(key)
                }
            }
        }
    }

    LaunchedEffect(elements) {
        val activeTextIds = elements.filterIsInstance<CanvasTextElement>().map { it.id }.toSet()
        textLayoutCache.keys.toList().forEach { cachedId ->
            if (cachedId !in activeTextIds) textLayoutCache.remove(cachedId)
        }
    }

    // Important: only install pointer handlers when needed; this overlay sits above the
    // drawing canvas so it must not block drawing/erase gestures unless it consumes them.
	    val gestureModifier = when {
	        (activeTool == DrawingTool.TEXT || activeTool == DrawingTool.STICKER || activeTool == DrawingTool.PHOTO) &&
	            !isEditorOpen ->
	            Modifier.pointerInput(elements, canvasSize, selectedColorArgb, activeTool) {
		            awaitEachGesture {
		                val down = awaitFirstDown(requireUnconsumed = false)
		                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@awaitEachGesture
		                val hittable = when (activeTool) {
		                    DrawingTool.TEXT -> elements.filterIsInstance<CanvasTextElement>()
		                    DrawingTool.STICKER -> elements.filterIsInstance<CanvasStickerElement>()
		                    DrawingTool.PHOTO -> elements.filterIsInstance<CanvasPhotoElement>()
		                    else -> elements
		                }

		                fun toContentOffset(point: Offset): Offset {
		                    val currentViewportTransform = latestViewportTransform
		                    val safeViewportScale = currentViewportTransform.scale.coerceAtLeast(0.0001f)
		                    return (point - currentViewportTransform.offsetPx) / safeViewportScale
		                }

		                fun hitTestAtScreenPosition(point: Offset): String? {
		                    val currentViewportTransform = latestViewportTransform
		                    val safeViewportScale = currentViewportTransform.scale.coerceAtLeast(0.0001f)
		                    val contentA = (point - currentViewportTransform.offsetPx) / safeViewportScale
		                    val hitA = hitTestAtContentPosition(
		                        elementsForHitTest = hittable,
		                        pointPx = contentA
		                    )
		                    if (hitA != null) return hitA

		                    // Defensive fallback for any transform-order mismatch between draw vs hit-test math.
		                    // (We want element interaction to win over viewport pan/zoom.)
		                    val contentB = (point / safeViewportScale) - currentViewportTransform.offsetPx
		                    return hitTestAtContentPosition(
		                        elementsForHitTest = hittable,
		                        pointPx = contentB
		                    )
		                }
		
		                val initialHitId = hitTestAtScreenPosition(down.position)

	                val initialDown = down.position
	                val touchSlop = viewConfiguration.touchSlop

	                var lockedElementId: String? = initialHitId
	                liveTransformPreview = null

                var beganTransform = false
                var beganDrag = false
                var sawMultiTouch = false
                var consumeGesture = false
                var lastCentroid = initialDown
                var lastAngle = 0f
                var lastSpan = 0f
                var lastPointerCount = 1
                var transformBaselineSet = false
                var baselineCentroid = initialDown
                var baselineAngle = 0f
                var baselineSpan = 0f
                // The twist as the fingers made it, before settling on a quarter turn.
                var twistRawRotation: Float? = null
                var twistSquared = false

                fun activeElement(): CanvasElement? {
                    val selected = lockedElementId ?: return null
                    return liveTransformPreview ?: hittable.firstOrNull { it.id == selected }
                }

		                // Track pointers until all are up.
		                while (true) {
		                    val event = awaitPointerEvent()
		                    // Held for the reactions: the element stays as it was.
		                    if (event.changes.any { it.isConsumed }) {
		                        liveTransformPreview = null
		                        isTransformInProgress = false
		                        return@awaitEachGesture
		                    }
		                    val pressed = event.changes.filter { it.pressed }
		                    if (pressed.isEmpty()) break

		                    val positions = pressed.map { it.position }
		                    val centroid = positions.reduce { acc, offset -> acc + offset } / positions.size.toFloat()

	                    val span = if (positions.size >= 2) {
	                        (positions[1] - positions[0]).getDistance()
	                    } else {
                        0f
                    }
                    val angle = if (positions.size >= 2) {
                        atan2(
                            (positions[1].y - positions[0].y),
                            (positions[1].x - positions[0].x)
                        )
                    } else {
                        0f
                    }

		                    // If the pointer count changes mid-gesture, rebase deltas to avoid jumps
		                    // (especially 2 pointers -> 1 pointer).
		                    if (positions.size != lastPointerCount) {
		                        lastCentroid = centroid
		                        if (positions.size >= 2) {
		                            lastAngle = angle
		                            lastSpan = span.coerceAtLeast(1f)
		                            transformBaselineSet = false
		                        }
		                        if (positions.size == 1) {
		                            beganTransform = false
		                        }
		                        lastPointerCount = positions.size
		                    }

	                    val panDelta = centroid - lastCentroid
                        val contentPanDelta = panDelta / latestViewportTransform.scale.coerceAtLeast(0.0001f)

	                    if (!beganTransform && positions.size >= 2 && !transformBaselineSet) {
	                        transformBaselineSet = true
	                        baselineCentroid = centroid
	                        baselineAngle = angle
                        baselineSpan = span.coerceAtLeast(1f)
                    }

                    val totalMove = (centroid - initialDown).getDistance()
	                    val baselineCentroidMove = (centroid - baselineCentroid).getDistance()
	                    val baselineSpanMove = kotlin.math.abs(span - baselineSpan)
	                    val baselineRotationMove = kotlin.math.abs(angle - baselineAngle) * baselineSpan

	                    if (
	                        !beganTransform &&
	                        positions.size >= 2 &&
	                        (baselineCentroidMove > touchSlop || baselineSpanMove > touchSlop || baselineRotationMove > touchSlop)
	                    ) {
		                        sawMultiTouch = true
		                        consumeGesture = true

		                        // Allow direct 2-finger transform without a prior tap selection by
		                        // picking the topmost element under the gesture centroid.
		                        if (lockedElementId == null) {
		                            lockedElementId = hitTestAtScreenPosition(centroid)
		                            lastCentroid = centroid
		                            lastAngle = angle
		                            lastSpan = span.coerceAtLeast(1f)
		                        }

                        // If we still don't have a target element, we can't transform; ignore.
                        if (lockedElementId == null) {
                            pressed.forEach { it.consume() }
                            lastCentroid = centroid
                            continue
                        }
                        beganTransform = true
                        beganDrag = true
                        isTransformInProgress = true
                        lastAngle = angle
                        lastSpan = span.coerceAtLeast(1f)
                    } else if (!beganDrag && positions.size == 1 && totalMove > touchSlop) {
                        // Single-finger drag only makes sense when we have a target element.
                        if (lockedElementId == null) {
                            if (consumeGesture) {
                                pressed.forEach { it.consume() }
                                lastCentroid = centroid
                            }
                            continue
                        }
	                        beganDrag = true
	                        isTransformInProgress = true
	                    }

                    if (beganDrag) {
                        val current = activeElement() ?: break
                        val zoomChange = if (beganTransform) {
                            val safeSpan = span.coerceAtLeast(1f)
                            safeSpan / lastSpan.coerceAtLeast(1f)
                        } else {
                            1f
                        }
                        val rotationDelta = if (beganTransform) {
                            (angle - lastAngle)
                        } else {
                            0f
                        }

                        val centerPx = current.center.denormalize(canvasSize)
                        val nextCenterPx = centerPx + contentPanDelta
                        val nextScale = when (current) {
                            is CanvasTextElement -> (current.scale * zoomChange).coerceIn(0.3f, 6f)
                            is CanvasStickerElement -> (current.scale * zoomChange).coerceIn(0.08f, 1.6f)
                            is CanvasPhotoElement -> (current.renderScale * zoomChange)
                                .coerceIn(CanvasPhotoElement.SCALE_MIN, CanvasPhotoElement.SCALE_MAX)
                        }
                        // Settles level, or on a quarter turn, within 3°, with a tick as it lands, as on iOS.
                        val nextRotation = if (beganTransform) {
                            val base = twistRawRotation ?: current.rotationRad.also {
                                twistSquared = ElementRotation.isSquare(it)
                            }
                            val raw = base + rotationDelta
                            twistRawRotation = raw
                            val settled = ElementRotation.settle(raw)
                            val square = ElementRotation.isSquare(settled)
                            if (square && !twistSquared) haptics.perform(MulberryHaptic.RotationSnap)
                            twistSquared = square
                            settled
                        } else {
                            twistRawRotation = null
                            current.rotationRad
                        }
                        liveTransformPreview = when (current) {
                            is CanvasTextElement -> current.copy(
                                center = nextCenterPx.toNormalizedPoint(canvasSize),
                                scale = nextScale,
                                rotationRad = nextRotation
                            )
                            is CanvasStickerElement -> current.copy(
                                center = nextCenterPx.toNormalizedPoint(canvasSize),
                                scale = nextScale,
                                rotationRad = nextRotation
                            )
                            is CanvasPhotoElement -> current.copy(
                                center = nextCenterPx.toNormalizedPoint(canvasSize),
                                scale = nextScale,
                                rotationRad = nextRotation
                            )
                        }

                        pressed.forEach { it.consume() }
                        lastCentroid = centroid
                        if (beganTransform) {
                            lastAngle = angle
                            lastSpan = span.coerceAtLeast(1f)
                        }
                    }

                    if (consumeGesture && !beganDrag) {
                        pressed.forEach { it.consume() }
                        lastCentroid = centroid
                    }
                }

	                // Gesture ended.
	                isTransformInProgress = false

	                if (lockedElementId == null && activeTool == DrawingTool.TEXT) {
	                    // Tap empty area => create + edit (text only).
	                    // If the user attempted a multi-touch gesture, never create a text element.
	                    if (sawMultiTouch) {
	                        liveTransformPreview = null
	                        return@awaitEachGesture
	                    }
                        if (!latestAllowCreateOnEmptyTap) {
                            liveTransformPreview = null
                            return@awaitEachGesture
                        }
	                    val id = UUID.randomUUID().toString()
                    val element = CanvasTextElement(
	                        id = id,
	                        text = "",
	                        createdAt = System.currentTimeMillis(),
                        center = toContentOffset(initialDown).toNormalizedPoint(canvasSize),
                        rotationRad = 0f,
                        scale = 1f,
                        boxWidth = 0.7f,
                        colorArgb = selectedColorArgb,
                        backgroundPillEnabled = false,
                        font = CanvasTextFont.POPPINS,
                        alignment = CanvasTextAlign.CENTER
                    )
                    onEmptyTapCreationHandled()
                    onRequestTextEdit(CanvasTextEditorSession(element = element, isNew = true))
                    return@awaitEachGesture
                } else if (lockedElementId == null && activeTool == DrawingTool.STICKER) {
                    // Sticker tool tap empty canvas => enter sticker edit mode.
                    // If the user attempted a multi-touch gesture, never open the picker.
                    if (sawMultiTouch) {
                        liveTransformPreview = null
                        return@awaitEachGesture
                    }
                    if (!latestAllowCreateOnEmptyTap) {
                        liveTransformPreview = null
                        return@awaitEachGesture
                    }
                    liveTransformPreview = null
                    onRequestNewStickerAt(toContentOffset(initialDown).toNormalizedPoint(canvasSize))
                    onEmptyTapCreationHandled()
                    return@awaitEachGesture
                } else if (lockedElementId == null && activeTool == DrawingTool.PHOTO) {
                    // The Camera tool on empty canvas opens the camera for another photo.
                    liveTransformPreview = null
                    if (sawMultiTouch || !latestAllowCreateOnEmptyTap) return@awaitEachGesture
                    onRequestNewPhoto()
                    onEmptyTapCreationHandled()
                    return@awaitEachGesture
                } else if (lockedElementId == null) {
                    liveTransformPreview = null
                    return@awaitEachGesture
                }

                val base = hittable.firstOrNull { it.id == lockedElementId }
                val preview = liveTransformPreview
                liveTransformPreview = null

                if (beganDrag) {
                    if (preview != null && base != null && preview != base) {
                        when (preview) {
                            is CanvasTextElement -> onUpdateTextElement(preview)
                            is CanvasStickerElement -> onUpdateStickerElement(preview)
                            is CanvasPhotoElement -> onUpdatePhotoElement(preview)
                        }
                    }
                } else {
                    // It's a tap on the element -> enter edit mode.
                    val target = base ?: return@awaitEachGesture
                    if (activeTool == DrawingTool.TEXT && target is CanvasTextElement) {
                        onRequestTextEdit(CanvasTextEditorSession(element = target, isNew = false))
                    } else if (activeTool == DrawingTool.STICKER && target is CanvasStickerElement) {
                        onRequestStickerEdit(CanvasStickerEditorSession(element = target, isNew = false))
                    } else if (activeTool == DrawingTool.PHOTO && target is CanvasPhotoElement) {
                        onRequestPhotoEdit(target)
                    }
                }
            }
        }
	        activeTool == DrawingTool.ERASE && !isEditorOpen -> Modifier.pointerInput(elements, canvasSize) {
	            awaitEachGesture {
	                val down = awaitFirstDown(requireUnconsumed = false)
	                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@awaitEachGesture
	                fun toContentOffset(point: Offset): Offset {
	                    val currentViewportTransform = latestViewportTransform
	                    val safeViewportScale = currentViewportTransform.scale.coerceAtLeast(0.0001f)
	                    return (point - currentViewportTransform.offsetPx) / safeViewportScale
	                }
	                fun toContentOffsetFallback(point: Offset): Offset {
	                    val currentViewportTransform = latestViewportTransform
	                    val safeViewportScale = currentViewportTransform.scale.coerceAtLeast(0.0001f)
	                    return (point / safeViewportScale) - currentViewportTransform.offsetPx
	                }

	                val hitId = hitTestAtContentPosition(
	                    elementsForHitTest = elements,
	                    pointPx = toContentOffset(down.position)
	                ) ?: hitTestAtContentPosition(
	                    elementsForHitTest = elements,
	                    pointPx = toContentOffsetFallback(down.position)
	                )

                // Consume so the parent pager/tab row doesn't treat this as a swipe-to-navigate.
                down.consume()
                val initialDown = down.position
                val touchSlop = viewConfiguration.touchSlop
                var movedBeyondSlop = false
                // Where the next erase segment starts, once the finger is dragging.
                var eraseFrom: Offset? = null

                // Wait until the pointer is released/cancelled. A drag erases every stroke it
                // crosses; a drag that starts on text or a sticker leaves it and erases from there.
                var pointer = down
                var heldForReactions = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointer.id }
                        ?: event.changes.firstOrNull()
                        ?: break
                    pointer = change
                    // Held for the reactions: the rest of the touch erases nothing.
                    if (change.isConsumed) {
                        heldForReactions = true
                        break
                    }
                    if (!movedBeyondSlop && (change.position - initialDown).getDistance() > touchSlop) {
                        movedBeyondSlop = true
                        eraseFrom = if (hitId != null) change.position else initialDown
                    }
                    val from = eraseFrom
                    if (from != null) {
                        onEraseDrag(
                            toContentOffset(from).toNormalizedPoint(canvasSize),
                            toContentOffset(change.position).toNormalizedPoint(canvasSize)
                        )
                        eraseFrom = change.position
                    }
                    if (!change.pressed) break
                    change.consume()
                }

                if (movedBeyondSlop || heldForReactions) return@awaitEachGesture

                if (hitId != null && !isStrokeAt(toContentOffset(initialDown))) {
                    val target = elements.firstOrNull { it.id == hitId }
                    when (target) {
                        is CanvasTextElement -> onDeleteTextElement(hitId)
                        is CanvasStickerElement -> onDeleteStickerElement(hitId)
                        is CanvasPhotoElement -> onDeletePhotoElement(hitId)
                        null -> Unit
                    }
                } else {
                    // Prefer the main transform math; fallback keeps behavior sensible if draw transform differs.
                    val content = toContentOffset(initialDown)
                    onEraseTap(content.toNormalizedPoint(canvasSize))
                }
            }
        }
        else -> Modifier
    }

    // All gestures are handled in a single pointerInput block (`gestureModifier`) so
    // tap-to-edit and drag-to-reposition work in the same gesture without requiring
    // a prior selection or recomposition.

    // Photos come from the photo store; the canvas redraws as each one is decoded.
    val loadedPhotoIds by photoStore.loadedPhotoIds.collectAsState()
    val photoIdsToLoad = remember(elements) {
        elements.filterIsInstance<CanvasPhotoElement>()
            .flatMap { listOfNotNull(it.photoId, it.cutoutPhotoId) }
            .distinct()
    }
    LaunchedEffect(photoIdsToLoad) {
        photoIdsToLoad.forEach { photoId -> launch { photoStore.loadBitmap(photoId) } }
    }
    // Each photo drawn once with its shadow, then copied while only its place or angle changes.
    val renderedPhotos = remember { mutableMapOf<String, RenderedCanvasPhoto>() }
    LaunchedEffect(elements) {
        val photoIds = elements.filterIsInstance<CanvasPhotoElement>().map { it.id }.toSet()
        renderedPhotos.keys.toList().forEach { id -> if (id !in photoIds) renderedPhotos.remove(id) }
    }

    fun renderedPhotoFor(element: CanvasPhotoElement, canvasWidth: Float): RenderedCanvasPhoto? {
        val image = photoStore.cachedBitmap(element.photoId)
        val cutout = element.cutoutPhotoId?.let(photoStore::cachedBitmap)
        val content = listOf(
            element.style, element.aspectRatio, element.photoId, image != null, element.cutoutPhotoId, cutout != null
        ).joinToString("|")
        val long = CanvasPhotoRenderer.longEdge(element, canvasWidth)
        val cached = renderedPhotos[element.id]
        // While pinched, the copy is scaled; it's drawn afresh at its new size once let go.
        if (cached != null && cached.content == content && (isTransformInProgress || kotlin.math.abs(cached.long - long) < 0.5f)) {
            return cached
        }
        val rendered = CanvasPhotoRenderer.render(element, image, cutout, canvasWidth) ?: return cached
        return RenderedCanvasPhoto(content, long, rendered).also { renderedPhotos[element.id] = it }
    }

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .then(gestureModifier)
	    ) {
	        Canvas(modifier = Modifier.fillMaxSize()) {
	            withTransform(
	                transformBlock = {
	                    scale(viewportTransform.scale, viewportTransform.scale, pivot = Offset.Zero)
	                    translate(viewportTransform.offsetPx.x, viewportTransform.offsetPx.y)
	                }
	            ) {
	                drawIntoCanvas { canvas ->
	                    val native = canvas.nativeCanvas
	                    val pillPaddingPx = with(density) { 12.dp.toPx() }
	                    val pillCornerPx = with(density) { 18.dp.toPx() }
	                    val placeholderCornerPx = with(density) { 18.dp.toPx() }
                        val debugStrokeWidthPx = with(density) { 1.5.dp.toPx() }
                        textBoundsPaint.strokeWidth = debugStrokeWidthPx
                        textPillBoundsPaint.strokeWidth = debugStrokeWidthPx
                        stickerBoundsPaint.strokeWidth = debugStrokeWidthPx

	                    // While dragged, an element keeps its place in its own layer, under the strokes.
	                    val preview = liveTransformPreview
	                    loadedPhotoIds
	                    elementsInPaintOrder.forEach { baseElement ->
	                        if (baseElement.id == hiddenElementId) return@forEach
	                        val element = if (preview != null && preview.id == baseElement.id) preview else baseElement
	                        when (element) {
	                        is CanvasTextElement -> {
	                            val center = element.center.denormalize(canvasSize)
	                            val cachedLayout = getTextLayoutFor(element)
                            val backgroundColor = element.colorArgb.toInt()
                            val textColor = if (element.backgroundPillEnabled) {
                                if (luminance(backgroundColor) > 0.55f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                            } else {
                                backgroundColor
                            }
                            cachedLayout.paint.color = textColor

                            native.save()
                            native.translate(center.x, center.y)
                            native.rotate((element.rotationRad * 180f / Math.PI).toFloat())
                            native.scale(element.scale, element.scale)

                            val left = -cachedLayout.layout.width / 2f
                            val top = -cachedLayout.layout.height / 2f
                            val tight = cachedLayout.tightBounds
                            val tightRect = RectF(
                                left + tight.left,
                                top + tight.top,
                                left + tight.right,
                                top + tight.bottom
                            )
                            if (element.backgroundPillEnabled) {
                                pillBackgroundPaint.color = backgroundColor
                                val pillRect = RectF(tightRect).apply { inset(-pillPaddingPx, -pillPaddingPx) }
                                native.drawRoundRect(pillRect, pillCornerPx, pillCornerPx, pillBackgroundPaint)
                                if (showElementBounds) {
                                    native.drawRoundRect(pillRect, pillCornerPx, pillCornerPx, textPillBoundsPaint)
                                }
                            }
                            if (showElementBounds) {
                                native.drawRect(tightRect, textBoundsPaint)
                            }
                            native.translate(left, top)
                            cachedLayout.layout.draw(native)

	                            native.restore()
	                        }
	                        is CanvasStickerElement -> {
                            val center = element.center.denormalize(canvasSize)
                            val key = element.artwork().key
                            val bitmap = stickerBitmapFor(element)

                            val maxSizePx = (element.scale.coerceIn(0.08f, 1.6f) * canvasSize.width.toFloat())
                                .coerceAtLeast(1f)
                            val renderSize = if (bitmap != null) {
                                resolveStickerRenderSizePx(
                                    maxSizePx = maxSizePx,
                                    bitmapWidthPx = bitmap.width,
                                    bitmapHeightPx = bitmap.height
                                )
                            } else {
                                com.subhajit.mulberry.stickers.StickerRenderSizePx(
                                    widthPx = maxSizePx,
                                    heightPx = maxSizePx
                                )
                            }
                            val left = -renderSize.widthPx / 2f
                            val top = -renderSize.heightPx / 2f
                            val rect = RectF(left, top, left + renderSize.widthPx, top + renderSize.heightPx)

                            native.save()
                            native.translate(center.x, center.y)
                            native.rotate((element.rotationRad * 180f / Math.PI).toFloat())

                            val reveal = stickerReveals[key]?.value ?: 1f
                            val placeholderPulsing = stickerPlaceholders[key]
                            if (placeholderPulsing != null) {
                                val base = if (placeholderPulsing) placeholderPulse?.value ?: 0.13f else 0.13f
                                val alpha = base * (if (bitmap != null) 1f - reveal else 1f)
                                if (alpha > 0f) {
                                    // Square, at the sticker's size, whatever shape the artwork turns out to be.
                                    val half = maxSizePx / 2f
                                    val square = RectF(-half, -half, half, half)
                                    val corner = min(placeholderCornerPx, maxSizePx * 0.2f)
                                    stickerPlaceholderPaint.color = placeholderInkArgb
                                    stickerPlaceholderPaint.alpha = (alpha * 255f).toInt()
                                    native.drawRoundRect(square, corner, corner, stickerPlaceholderPaint)
                                }
                            }
                            if (bitmap != null) {
                                val src = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                                stickerBitmapPaint.alpha = (reveal * 255f).toInt()
                                native.drawBitmap(bitmap, src, rect, stickerBitmapPaint)
                                stickerBitmapPaint.alpha = 255
                            }
                            if (showElementBounds) {
                                native.drawRect(rect, stickerBoundsPaint)
                            }

	                            native.restore()
	                        }
	                        is CanvasPhotoElement -> {
	                            val canvasWidth = canvasSize.width.toFloat()
	                            val photo = renderedPhotoFor(element, canvasWidth)
	                            if (photo != null) {
	                                val center = element.center.denormalize(canvasSize)
	                                val grow = CanvasPhotoRenderer.longEdge(element, canvasWidth) / photo.long
	                                native.save()
	                                native.translate(center.x, center.y)
	                                native.rotate((element.rotationRad * 180f / Math.PI).toFloat())
	                                native.scale(grow, grow)
	                                native.drawBitmap(
	                                    photo.rendered.bitmap,
	                                    -photo.rendered.width / 2f,
	                                    -photo.rendered.height / 2f,
	                                    stickerBitmapPaint
	                                )
	                                native.restore()
	                            }
	                        }
	                        }
	                    }
	                }
	            }
	        }

        // Delete is now handled inside the text edit toolbar (not on-canvas).
    }
}

private enum class TextEditorPanel {
    NONE,
    FONT,
    SIZE,
    ROTATION,
    COLOR,
}

@Composable
fun TextEditorOverlay(
    element: CanvasTextElement,
    palette: List<Long>,
    autoFocus: Boolean,
    onDismiss: () -> Unit,
    onDone: (CanvasTextElement) -> Unit,
    onDelete: (String) -> Unit
) {
    var textField by remember(element.id) { mutableStateOf(TextFieldValue(element.text)) }
    var font by remember(element.id) { mutableStateOf(element.font) }
    var alignment by remember(element.id) { mutableStateOf(element.alignment) }
    var backgroundPillEnabled by remember(element.id) { mutableStateOf(element.backgroundPillEnabled) }
    var colorArgb by remember(element.id) { mutableStateOf(element.colorArgb) }
    var scale by remember(element.id) { mutableStateOf(element.scale.coerceIn(0.3f, 6f)) }
    var rotationDeg by remember(element.id) {
        mutableStateOf(radToDeg(element.rotationRad).coerceIn(ROTATION_MIN_DEG, ROTATION_MAX_DEG))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var panel by remember(element.id) { mutableStateOf(TextEditorPanel.NONE) }
    var enter by remember(element.id) { mutableStateOf(false) }
    var showCustomColorPicker by remember(element.id) { mutableStateOf(false) }
    val fontResolver = rememberCanvasFontResolver()

    // A tick for each font, alignment and colour picked, and as the rotation settles on level, as on iOS.
    HapticOnChange(font, MulberryHaptic.Selection)
    HapticOnChange(alignment, MulberryHaptic.Selection)
    HapticOnChange(colorArgb, MulberryHaptic.Selection)
    HapticOnChange(rotationDeg == 0f, MulberryHaptic.RotationSnap)

    LaunchedEffect(element.id) {
        enter = true
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (enter) 1f else 0f,
        animationSpec = tween(durationMillis = 110, easing = FastOutLinearInEasing),
        label = "textEditorOverlayAlpha"
    )

    val contentAlpha by animateFloatAsState(
        targetValue = if (enter) 1f else 0f,
        animationSpec = tween(durationMillis = 120, delayMillis = 45, easing = FastOutSlowInEasing),
        label = "textEditorContentAlpha"
    )

    val contentScale by animateFloatAsState(
        targetValue = if (enter) 1f else 0.98f,
        animationSpec = tween(durationMillis = 120, delayMillis = 45, easing = FastOutSlowInEasing),
        label = "textEditorContentScale"
    )

    fun commitAndExit() {
        val nextText = textField.text
        keyboardController?.hide()
        if (nextText.isBlank()) {
            onDelete(element.id)
            return
        }

        onDone(
            element.copy(
                text = nextText,
                font = font,
                alignment = alignment,
                backgroundPillEnabled = backgroundPillEnabled,
                colorArgb = colorArgb,
                scale = scale,
                rotationRad = degToRad(rotationDeg)
            )
        )
    }

    fun deleteAndExit() {
        keyboardController?.hide()
        onDelete(element.id)
    }

    LaunchedEffect(element.id, autoFocus) {
        // In v1, keep the keyboard open for the entire edit session (new or existing text).
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    BackHandler(onBack = { commitAndExit() })

    val backgroundColor = Color(colorArgb)
    val textColor = if (backgroundPillEnabled) {
        val isLight = luminance(colorArgb.toInt()) > 0.55f
        if (isLight) Color.Black else Color.White
    } else {
        backgroundColor
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f * overlayAlpha))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Background hit target: tapping outside the text/controls exits edit mode.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { commitAndExit() }
                }
        )

        // Top "Done"
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 18.dp, vertical = 12.dp)
                .alpha(contentAlpha)
        ) {
            Text(
                text = "Done",
                color = Color.White,
                fontFamily = PoppinsFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        commitAndExit()
                    }
            )
        }

        // Centered text editor
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 64.dp, bottom = 96.dp),
            contentAlignment = Alignment.Center
        ) {
            val pillModifier = if (backgroundPillEnabled) {
                Modifier
                    .background(backgroundColor, RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            } else {
                Modifier
            }

            Box(
                modifier = pillModifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
                    .graphicsLayer {
                        scaleX = contentScale
                        scaleY = contentScale
                        rotationZ = rotationDeg
                    }
                    .alpha(contentAlpha),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = textField,
                    onValueChange = { next -> textField = next },
                    modifier = Modifier
                        .fillMaxWidth(fraction = element.boxWidth.coerceIn(0.35f, 0.95f))
                        .focusRequester(focusRequester),
                    cursorBrush = SolidColor(textColor),
                    textStyle = TextStyle(
                        color = textColor,
                        fontFamily = fontResolver.fontFamilyFor(font),
                        fontSize = (34f * scale).coerceIn(12f, 160f).sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = when (alignment) {
                            CanvasTextAlign.LEFT -> TextAlign.Left
                            CanvasTextAlign.CENTER -> TextAlign.Center
                            CanvasTextAlign.RIGHT -> TextAlign.Right
                        }
                    ),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (textField.text.isBlank()) {
                                Text(
                                    text = "Type something",
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontFamily = PoppinsFontFamily,
                                    fontSize = 28.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            inner()
                        }
                    }
                )
            }
        }

        // Bottom toolbars (secondary + optional tertiary)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .alpha(contentAlpha),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (panel) {
                TextEditorPanel.FONT -> {
                    val scroll = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scroll)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CanvasTextFont.entries.forEach { option ->
                            FontOption(
                                label = option.canvasDisplayName(),
                                fontFamily = fontResolver.fontFamilyFor(option),
                                selected = font == option
                            ) {
                                font = option
                                focusRequester.requestFocus()
                            }
                        }
                    }
                }

                TextEditorPanel.SIZE -> {
                    EditorTertiaryBar {
                        Text(
                            text = "Aa",
                            color = Color.White,
                            modifier = Modifier.clearAndSetSemantics { },
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        androidx.compose.material3.Slider(
                            value = scale,
                            onValueChange = { scale = it },
                            valueRange = 0.3f..6f,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "Size"
                                    stateDescription = "${(scale * 100).roundToInt()} percent"
                                }
                        )
                        Text(
                            text = "AA",
                            color = Color.White,
                            modifier = Modifier.clearAndSetSemantics { },
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }

                TextEditorPanel.ROTATION -> {
                    EditorTertiaryBar {
                        androidx.compose.material3.Slider(
                            value = rotationDeg.coerceIn(ROTATION_MIN_DEG, ROTATION_MAX_DEG),
                            onValueChange = { value ->
                                rotationDeg = ElementRotation.settleSliderDegrees(
                                    value.coerceIn(ROTATION_MIN_DEG, ROTATION_MAX_DEG)
                                )
                                focusRequester.requestFocus()
                            },
                            valueRange = ROTATION_MIN_DEG..ROTATION_MAX_DEG,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "Rotation"
                                    stateDescription = "${rotationDeg.roundToInt()} degrees"
                                }
                        )
                    }
                }

                TextEditorPanel.COLOR -> {
                    val scroll = rememberScrollState()
                    val customSelected = colorArgb !in palette
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scroll)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        palette.forEach { candidate ->
                            val selected = candidate == colorArgb
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(candidate), CircleShape)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) MulberryPrimary else Color.White.copy(alpha = 0.22f),
                                        shape = CircleShape
                                    )
                                    .selectable(
                                        selected = selected,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        role = Role.Button
                                    ) {
                                        colorArgb = candidate
                                        focusRequester.requestFocus()
                                    }
                                    .semantics { contentDescription = ColourNames.name(candidate) }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .then(
                                    if (customSelected) {
                                        Modifier.border(2.dp, MulberryPrimary, CircleShape)
                                    } else {
                                        Modifier
                                    }
                                )
                                .selectable(
                                    selected = customSelected,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Button
                                ) {
                                    showCustomColorPicker = true
                                    focusRequester.requestFocus()
                                }
                                .semantics { contentDescription = "Custom colour" },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_text_toolbar_color),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                TextEditorPanel.NONE -> Unit
            }

            EditorSecondaryBar(
                panel = panel,
                onPanelChanged = { next ->
                    panel = if (panel == next) TextEditorPanel.NONE else next
                    focusRequester.requestFocus()
                },
                alignment = alignment,
                onCycleAlignment = {
                    alignment = when (alignment) {
                        CanvasTextAlign.LEFT -> CanvasTextAlign.CENTER
                        CanvasTextAlign.CENTER -> CanvasTextAlign.RIGHT
                        CanvasTextAlign.RIGHT -> CanvasTextAlign.LEFT
                    }
                    focusRequester.requestFocus()
                },
                backgroundPillEnabled = backgroundPillEnabled,
                onTogglePill = {
                    backgroundPillEnabled = !backgroundPillEnabled
                    focusRequester.requestFocus()
                },
                onDelete = { deleteAndExit() }
            )
        }
    }

    RgbColorPickerSheet(
        visible = showCustomColorPicker,
        initialColorArgb = colorArgb,
        onDismissRequest = { showCustomColorPicker = false },
        onApply = { picked ->
            colorArgb = picked
            showCustomColorPicker = false
            focusRequester.requestFocus()
        }
    )
}

@Composable
private fun FontOption(
    label: String,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MulberryPrimary else Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        androidx.compose.material3.Text(
            text = label,
            color = Color.White,
            fontFamily = fontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EditorSecondaryBar(
    panel: TextEditorPanel,
    onPanelChanged: (TextEditorPanel) -> Unit,
    alignment: CanvasTextAlign,
    onCycleAlignment: () -> Unit,
    backgroundPillEnabled: Boolean,
    onTogglePill: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorIconButton(
            iconRes = R.drawable.ic_text_toolbar_font,
            contentDescription = "Font",
            selected = panel == TextEditorPanel.FONT
        ) { onPanelChanged(TextEditorPanel.FONT) }
        EditorIconButton(
            iconRes = R.drawable.ic_text_toolbar_size,
            contentDescription = "Size",
            selected = panel == TextEditorPanel.SIZE
        ) { onPanelChanged(TextEditorPanel.SIZE) }
        EditorIconButton(
            iconRes = R.drawable.ic_sticker_toolbar_rotate,
            contentDescription = "Rotation",
            selected = panel == TextEditorPanel.ROTATION
        ) { onPanelChanged(TextEditorPanel.ROTATION) }
        EditorIconButton(
            iconRes = R.drawable.ic_text_toolbar_color,
            contentDescription = "Colour",
            selected = panel == TextEditorPanel.COLOR,
            tint = Color.Unspecified
        ) { onPanelChanged(TextEditorPanel.COLOR) }

        val alignIcon = when (alignment) {
            CanvasTextAlign.LEFT -> R.drawable.ic_text_toolbar_align_left
            CanvasTextAlign.CENTER -> R.drawable.ic_text_toolbar_align_center
            CanvasTextAlign.RIGHT -> R.drawable.ic_text_toolbar_align_right
        }
        EditorIconButton(
            iconRes = alignIcon,
            contentDescription = "Alignment",
            selected = false,
            stateDescription = when (alignment) {
                CanvasTextAlign.LEFT -> "Left"
                CanvasTextAlign.CENTER -> "Centre"
                CanvasTextAlign.RIGHT -> "Right"
            }
        ) { onCycleAlignment() }

        Spacer(modifier = Modifier.weight(1f))

        EditorIconButton(
            iconRes = R.drawable.ic_text_toolbar_pill,
            contentDescription = "Background",
            selected = backgroundPillEnabled
        ) { onTogglePill() }
        EditorIconButton(
            iconRes = R.drawable.ic_text_toolbar_delete,
            contentDescription = "Delete",
            selected = false
        ) { onDelete() }
    }
}

@Composable
private fun EditorTertiaryBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun EditorIconButton(
    iconRes: Int,
    contentDescription: String,
    selected: Boolean,
    tint: Color = Color.White,
    stateDescription: String? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(Color.White.copy(alpha = 0.10f), CircleShape)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MulberryPrimary, CircleShape)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button
            ) { onClick() }
            .semantics(mergeDescendants = true) {
                this.selected = selected
                stateDescription?.let { this.stateDescription = it }
            },
        contentAlignment = Alignment.Center
    ) {
        // Use Image for bitmap resources (e.g. the color icon); Icon for vectors.
        if (tint == Color.Unspecified) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private fun StrokePoint.denormalize(canvasSize: IntSize): Offset =
    Offset(x = x * canvasSize.width.toFloat(), y = y * canvasSize.height.toFloat())

private fun Offset.toNormalizedPoint(canvasSize: IntSize): StrokePoint {
    val safeW = canvasSize.width.coerceAtLeast(1).toFloat()
    val safeH = canvasSize.height.coerceAtLeast(1).toFloat()
    return StrokePoint(
        x = (x / safeW).coerceIn(0f, 1f),
        y = (y / safeH).coerceIn(0f, 1f)
    )
}

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

private fun luminance(color: Int): Float {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun Float.degreesToRadians(): Float = (this * PI.toFloat()) / 180f

private const val ROTATION_MIN_DEG = -180f
private const val ROTATION_MAX_DEG = 180f

private fun radToDeg(rad: Float): Float = (rad * 180f / PI.toFloat())

private fun degToRad(deg: Float): Float = (deg * PI.toFloat() / 180f)

/** A photo drawn with its shadow, at [long] canvas pixels on its long edge, for content [content]. */
private class RenderedCanvasPhoto(
    val content: String,
    val long: Float,
    val rendered: CanvasPhotoRenderer.Rendered
)
