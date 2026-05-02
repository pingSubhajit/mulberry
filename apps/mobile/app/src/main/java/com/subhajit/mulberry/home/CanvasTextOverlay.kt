package com.subhajit.mulberry.home

import android.graphics.RectF
import android.graphics.Typeface
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import com.subhajit.mulberry.R
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.CanvasStickerElement
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.stickers.StickerAssetStore
import com.subhajit.mulberry.stickers.StickerAssetVariant
import com.subhajit.mulberry.stickers.resolveStickerRenderSizePx
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.VirgilFontFamily
import com.subhajit.mulberry.ui.theme.DmSansFontFamily
import com.subhajit.mulberry.ui.theme.SpaceMonoFontFamily
import com.subhajit.mulberry.ui.theme.PlayfairDisplayFontFamily
import com.subhajit.mulberry.ui.theme.BangersFontFamily
import com.subhajit.mulberry.ui.theme.PermanentMarkerFontFamily
import com.subhajit.mulberry.ui.theme.KalamFontFamily
import com.subhajit.mulberry.ui.theme.CaveatFontFamily
import com.subhajit.mulberry.ui.theme.MerriweatherFontFamily
import com.subhajit.mulberry.ui.theme.OswaldFontFamily
import com.subhajit.mulberry.ui.theme.Baloo2FontFamily
import com.subhajit.mulberry.ui.theme.MulberryPrimary
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.math.PI
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

data class CanvasTextEditorSession(
    val element: CanvasTextElement,
    val isNew: Boolean
)

@Composable
fun CanvasTextOverlay(
    elements: List<CanvasElement>,
    activeTool: DrawingTool,
    palette: List<Long>,
    selectedColorArgb: Long,
    stickerAssetStore: StickerAssetStore,
    onEraseTap: (StrokePoint) -> Unit,
    onAddTextElement: (CanvasTextElement) -> Unit,
    onUpdateTextElement: (CanvasTextElement) -> Unit,
    onDeleteTextElement: (String) -> Unit,
    onAddStickerElement: (CanvasStickerElement) -> Unit,
    onUpdateStickerElement: (CanvasStickerElement) -> Unit,
    onDeleteStickerElement: (String) -> Unit,
    onRequestTextEdit: (CanvasTextEditorSession) -> Unit,
    onRequestStickerEdit: (CanvasStickerEditorSession) -> Unit,
    onRequestNewStickerAt: (StrokePoint) -> Unit,
    isEditorOpen: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedElementId by remember { mutableStateOf<String?>(null) }
    var liveTransformPreview by remember { mutableStateOf<CanvasElement?>(null) }
    val stickerBitmaps = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }

    LaunchedEffect(activeTool) {
        if (activeTool != DrawingTool.TEXT && activeTool != DrawingTool.STICKER) {
            selectedElementId = null
            liveTransformPreview = null
        }
    }

    LaunchedEffect(isEditorOpen) {
        if (!isEditorOpen) {
            selectedElementId = null
            liveTransformPreview = null
        }
    }

    val poppinsTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.poppins_regular) ?: Typeface.DEFAULT }
    val virgilTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.virgil_regular) ?: Typeface.DEFAULT }
    val dmSansTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.dm_sans_regular) ?: Typeface.DEFAULT }
    val spaceMonoTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.space_mono_regular) ?: Typeface.DEFAULT }
    val playfairTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.playfair_display_regular) ?: Typeface.DEFAULT }
    val bangersTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.bangers_regular) ?: Typeface.DEFAULT }
    val permanentMarkerTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.permanent_marker_regular) ?: Typeface.DEFAULT }
    val kalamTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.kalam_regular) ?: Typeface.DEFAULT }
    val caveatTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.caveat_regular) ?: Typeface.DEFAULT }
    val merriweatherTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.merriweather_regular) ?: Typeface.DEFAULT }
    val oswaldTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.oswald_regular) ?: Typeface.DEFAULT }
    val baloo2Typeface = remember(context) { ResourcesCompat.getFont(context, R.font.baloo2_regular) ?: Typeface.DEFAULT }
    val baseTextSizePx = with(density) { 34.sp.toPx() }
    var isTransformInProgress by remember { mutableStateOf(false) }

    val stickerElements = remember(elements) { elements.filterIsInstance<CanvasStickerElement>() }
    LaunchedEffect(stickerElements) {
        stickerElements.forEach { element ->
            val key = "${element.packKey}:${element.packVersion}:${element.stickerId}:full"
            if (stickerBitmaps.containsKey(key)) return@forEach
            val file = stickerAssetStore.getOrDownloadStickerAsset(
                packKey = element.packKey,
                packVersion = element.packVersion,
                stickerId = element.stickerId,
                variant = StickerAssetVariant.FULL
            ) ?: return@forEach
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return@forEach
            stickerBitmaps[key] = bitmap
        }
    }

    // Important: only install pointer handlers when needed; this overlay sits above the
    // drawing canvas so it must not block drawing/erase gestures unless it consumes them.
    val gestureModifier = when {
        (activeTool == DrawingTool.TEXT || activeTool == DrawingTool.STICKER) && !isEditorOpen ->
            Modifier.pointerInput(elements, canvasSize, selectedColorArgb, activeTool) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@awaitEachGesture
                val hittable = when (activeTool) {
                    DrawingTool.TEXT -> elements.filterIsInstance<CanvasTextElement>()
                    DrawingTool.STICKER -> elements.filterIsInstance<CanvasStickerElement>()
                    else -> elements
                }

                val hitId = hitTest(
                    elements = hittable,
                    stickerBitmaps = stickerBitmaps,
                    pointPx = down.position,
                    canvasSize = canvasSize,
                    textSizePx = baseTextSizePx,
                    poppins = poppinsTypeface,
                    virgil = virgilTypeface,
                    dmSans = dmSansTypeface,
                    spaceMono = spaceMonoTypeface,
                    playfair = playfairTypeface,
                    bangers = bangersTypeface,
                    permanentMarker = permanentMarkerTypeface,
                    kalam = kalamTypeface,
                    caveat = caveatTypeface,
                    merriweather = merriweatherTypeface,
                    oswald = oswaldTypeface,
                    baloo2 = baloo2Typeface
                )
                val initialDown = down.position
                val touchSlop = viewConfiguration.touchSlop

                selectedElementId = hitId
                liveTransformPreview = null

                var beganTransform = false
                var beganDrag = false
                var lastCentroid = initialDown
                var lastAngle = 0f
                var lastSpan = 0f

                fun activeElement(): CanvasElement? {
                    val selected = selectedElementId ?: return null
                    return liveTransformPreview ?: hittable.firstOrNull { it.id == selected }
                }

                // Track pointers until all are up.
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break

                    if (hitId == null) {
                        // Not on a text element; let the user complete the tap to create.
                        continue
                    }

                    val current = activeElement() ?: break
                    val positions = pressed.map { it.position }
                    val centroid = positions.reduce { acc, offset -> acc + offset } / positions.size.toFloat()
                    val panDelta = centroid - lastCentroid

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

                    val totalMove = (centroid - initialDown).getDistance()
                    if (!beganTransform && positions.size >= 2 && totalMove > touchSlop) {
                        beganTransform = true
                        beganDrag = true
                        isTransformInProgress = true
                        lastAngle = angle
                        lastSpan = span.coerceAtLeast(1f)
                    } else if (!beganDrag && positions.size == 1 && totalMove > touchSlop) {
                        beganDrag = true
                        isTransformInProgress = true
                    }

                    if (beganDrag) {
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
                        val nextCenterPx = centerPx + panDelta
                        val nextScale = when (current) {
                            is CanvasTextElement -> (current.scale * zoomChange).coerceIn(0.3f, 6f)
                            is CanvasStickerElement -> (current.scale * zoomChange).coerceIn(0.08f, 1.6f)
                        }
                        val nextRotation = current.rotationRad + rotationDelta
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
                        }

                        pressed.forEach { it.consume() }
                        lastCentroid = centroid
                        if (beganTransform) {
                            lastAngle = angle
                            lastSpan = span.coerceAtLeast(1f)
                        }
                    }
                }

                // Gesture ended.
                isTransformInProgress = false

                if (hitId == null && activeTool == DrawingTool.TEXT) {
                    // Tap empty area => create + edit (text only).
                    val id = UUID.randomUUID().toString()
                    val element = CanvasTextElement(
                        id = id,
                        text = "",
                        createdAt = System.currentTimeMillis(),
                        center = initialDown.toNormalizedPoint(canvasSize),
                        rotationRad = 0f,
                        scale = 1f,
                        boxWidth = 0.7f,
                        colorArgb = selectedColorArgb,
                        backgroundPillEnabled = false,
                        font = CanvasTextFont.POPPINS,
                        alignment = CanvasTextAlign.CENTER
                    )
                    onAddTextElement(element)
                    selectedElementId = null
                    onRequestTextEdit(CanvasTextEditorSession(element = element, isNew = true))
                    return@awaitEachGesture
                } else if (hitId == null && activeTool == DrawingTool.STICKER) {
                    // Sticker tool tap empty canvas => enter sticker edit mode.
                    selectedElementId = null
                    liveTransformPreview = null
                    onRequestNewStickerAt(initialDown.toNormalizedPoint(canvasSize))
                    return@awaitEachGesture
                } else if (hitId == null) {
                    selectedElementId = null
                    liveTransformPreview = null
                    return@awaitEachGesture
                }

                val base = hittable.firstOrNull { it.id == hitId }
                val preview = liveTransformPreview
                liveTransformPreview = null

                if (beganDrag) {
                    if (preview != null && base != null && preview != base) {
                        when (preview) {
                            is CanvasTextElement -> onUpdateTextElement(preview)
                            is CanvasStickerElement -> onUpdateStickerElement(preview)
                        }
                    }
                    selectedElementId = null
                } else {
                    // It's a tap on the element -> enter edit mode.
                    val target = base ?: return@awaitEachGesture
                    selectedElementId = null
                    if (activeTool == DrawingTool.TEXT && target is CanvasTextElement) {
                        onRequestTextEdit(CanvasTextEditorSession(element = target, isNew = false))
                    } else if (activeTool == DrawingTool.STICKER && target is CanvasStickerElement) {
                        onRequestStickerEdit(CanvasStickerEditorSession(element = target, isNew = false))
                    }
                }
            }
        }
        activeTool == DrawingTool.ERASE && !isEditorOpen -> Modifier.pointerInput(elements, canvasSize) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@awaitEachGesture

                val hitId = hitTest(
                    elements = elements,
                    stickerBitmaps = stickerBitmaps,
                    pointPx = down.position,
                    canvasSize = canvasSize,
                    textSizePx = baseTextSizePx,
                    poppins = poppinsTypeface,
                    virgil = virgilTypeface,
                    dmSans = dmSansTypeface,
                    spaceMono = spaceMonoTypeface,
                    playfair = playfairTypeface,
                    bangers = bangersTypeface,
                    permanentMarker = permanentMarkerTypeface,
                    kalam = kalamTypeface,
                    caveat = caveatTypeface,
                    merriweather = merriweatherTypeface,
                    oswald = oswaldTypeface,
                    baloo2 = baloo2Typeface
                )

                // Consume so the parent pager/tab row doesn't treat this as a swipe-to-navigate.
                down.consume()
                val initialDown = down.position
                val touchSlop = viewConfiguration.touchSlop
                var movedBeyondSlop = false

                // Wait until the pointer is released/cancelled.
                var pointer = down
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointer.id }
                        ?: event.changes.firstOrNull()
                        ?: break
                    pointer = change
                    if (!change.pressed) break
                    if (!movedBeyondSlop && (change.position - initialDown).getDistance() > touchSlop) {
                        movedBeyondSlop = true
                    }
                    change.consume()
                }

                if (movedBeyondSlop) return@awaitEachGesture

                if (hitId != null) {
                    val target = elements.firstOrNull { it.id == hitId }
                    when (target) {
                        is CanvasTextElement -> onDeleteTextElement(hitId)
                        is CanvasStickerElement -> onDeleteStickerElement(hitId)
                        else -> onDeleteTextElement(hitId)
                    }
                } else {
                    onEraseTap(initialDown.toNormalizedPoint(canvasSize))
                }
            }
        }
        else -> Modifier
    }

    // All gestures are handled in a single pointerInput block (`gestureModifier`) so
    // tap-to-edit and drag-to-reposition work in the same gesture without requiring
    // a prior selection or recomposition.

    val boundsAlpha by animateFloatAsState(
        targetValue = if (
            (activeTool == DrawingTool.TEXT || activeTool == DrawingTool.STICKER) &&
                !isEditorOpen &&
                isTransformInProgress
        ) {
            1f
        } else {
            0f
        },
        animationSpec = tween(durationMillis = 140),
        label = "textBoundsAlpha"
    )

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .then(gestureModifier)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val pillPaddingPx = with(density) { 12.dp.toPx() }
                val pillCornerPx = with(density) { 18.dp.toPx() }

                val renderList = elements.map { element ->
                    if (element.id == liveTransformPreview?.id) liveTransformPreview!! else element
                }

                renderList.forEach { element ->
                    when (element) {
                        is CanvasTextElement -> {
                            val center = element.center.denormalize(canvasSize)
                            val wrapWidth = (element.boxWidth * canvasSize.width).toInt().coerceAtLeast(1)
                            val typeface = when (element.font) {
                                CanvasTextFont.POPPINS -> poppinsTypeface
                                CanvasTextFont.VIRGIL -> virgilTypeface
                                CanvasTextFont.DM_SANS -> dmSansTypeface
                                CanvasTextFont.SPACE_MONO -> spaceMonoTypeface
                                CanvasTextFont.PLAYFAIR_DISPLAY -> playfairTypeface
                                CanvasTextFont.BANGERS -> bangersTypeface
                                CanvasTextFont.PERMANENT_MARKER -> permanentMarkerTypeface
                                CanvasTextFont.KALAM -> kalamTypeface
                                CanvasTextFont.CAVEAT -> caveatTypeface
                                CanvasTextFont.MERRIWEATHER -> merriweatherTypeface
                                CanvasTextFont.OSWALD -> oswaldTypeface
                                CanvasTextFont.BALOO_2 -> baloo2Typeface
                            }
                            val backgroundColor = element.colorArgb.toInt()
                            val textColor = if (element.backgroundPillEnabled) {
                                if (luminance(backgroundColor) > 0.55f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                            } else {
                                backgroundColor
                            }

                            val paint = TextPaint().apply {
                                isAntiAlias = true
                                color = textColor
                                textSize = baseTextSizePx
                                this.typeface = typeface
                            }

                            val alignment = when (element.alignment) {
                                CanvasTextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                                CanvasTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                                CanvasTextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                            }

                            val layout = StaticLayout.Builder.obtain(element.text, 0, element.text.length, paint, wrapWidth)
                                .setAlignment(alignment)
                                .setIncludePad(false)
                                .build()

                            native.save()
                            native.translate(center.x, center.y)
                            native.rotate((element.rotationRad * 180f / Math.PI).toFloat())
                            native.scale(element.scale, element.scale)

                            val left = -layout.width / 2f
                            val top = -layout.height / 2f
                            if (element.backgroundPillEnabled) {
                                val pillPaint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    color = backgroundColor
                                }
                                val rect = RectF(
                                    left - pillPaddingPx,
                                    top - pillPaddingPx,
                                    left + layout.width + pillPaddingPx,
                                    top + layout.height + pillPaddingPx
                                )
                                native.drawRoundRect(rect, pillCornerPx, pillCornerPx, pillPaint)
                            }
                            native.translate(left, top)
                            layout.draw(native)

                            native.restore()

                            if (element.id == selectedElementId && boundsAlpha > 0f) {
                                val outlinePaint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = with(density) { 2.dp.toPx() }
                                    color = android.graphics.Color.argb(
                                        (0x66 * boundsAlpha).toInt().coerceIn(0, 255),
                                        255,
                                        255,
                                        255
                                    )
                                }
                                native.save()
                                native.translate(center.x, center.y)
                                native.rotate((element.rotationRad * 180f / Math.PI).toFloat())
                                native.scale(element.scale, element.scale)
                                val outline = RectF(left, top, left + layout.width, top + layout.height)
                                native.drawRoundRect(outline, pillCornerPx, pillCornerPx, outlinePaint)
                                native.restore()
                            }
                        }
                        is CanvasStickerElement -> {
                            val center = element.center.denormalize(canvasSize)
                            val key = "${element.packKey}:${element.packVersion}:${element.stickerId}:full"
                            val bitmap = stickerBitmaps[key]

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

                            if (bitmap != null) {
                                val src = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                                val paint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    isFilterBitmap = true
                                }
                                native.drawBitmap(bitmap, src, rect, paint)
                            } else {
                                val placeholderPaint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    color = android.graphics.Color.argb(64, 255, 255, 255)
                                }
                                native.drawRoundRect(rect, 18f, 18f, placeholderPaint)
                            }

                            if (element.id == selectedElementId && boundsAlpha > 0f) {
                                val outlinePaint = android.graphics.Paint().apply {
                                    isAntiAlias = true
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeWidth = with(density) { 2.dp.toPx() }
                                    color = android.graphics.Color.argb(
                                        (0x66 * boundsAlpha).toInt().coerceIn(0, 255),
                                        255,
                                        255,
                                        255
                                    )
                                }
                                native.drawRoundRect(rect, pillCornerPx, pillCornerPx, outlinePaint)
                            }

                            native.restore()
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
                        fontFamily = when (font) {
                            CanvasTextFont.POPPINS -> PoppinsFontFamily
                            CanvasTextFont.VIRGIL -> VirgilFontFamily
                            CanvasTextFont.DM_SANS -> DmSansFontFamily
                            CanvasTextFont.SPACE_MONO -> SpaceMonoFontFamily
                            CanvasTextFont.PLAYFAIR_DISPLAY -> PlayfairDisplayFontFamily
                            CanvasTextFont.BANGERS -> BangersFontFamily
                            CanvasTextFont.PERMANENT_MARKER -> PermanentMarkerFontFamily
                            CanvasTextFont.KALAM -> KalamFontFamily
                            CanvasTextFont.CAVEAT -> CaveatFontFamily
                            CanvasTextFont.MERRIWEATHER -> MerriweatherFontFamily
                            CanvasTextFont.OSWALD -> OswaldFontFamily
                            CanvasTextFont.BALOO_2 -> Baloo2FontFamily
                        },
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
                        FontOption(
                            label = "Poppins",
                            fontFamily = PoppinsFontFamily,
                            selected = font == CanvasTextFont.POPPINS
                        ) {
                            font = CanvasTextFont.POPPINS
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Virgil",
                            fontFamily = VirgilFontFamily,
                            selected = font == CanvasTextFont.VIRGIL
                        ) {
                            font = CanvasTextFont.VIRGIL
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "DM Sans",
                            fontFamily = DmSansFontFamily,
                            selected = font == CanvasTextFont.DM_SANS
                        ) {
                            font = CanvasTextFont.DM_SANS
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Space Mono",
                            fontFamily = SpaceMonoFontFamily,
                            selected = font == CanvasTextFont.SPACE_MONO
                        ) {
                            font = CanvasTextFont.SPACE_MONO
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Playfair",
                            fontFamily = PlayfairDisplayFontFamily,
                            selected = font == CanvasTextFont.PLAYFAIR_DISPLAY
                        ) {
                            font = CanvasTextFont.PLAYFAIR_DISPLAY
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Bangers",
                            fontFamily = BangersFontFamily,
                            selected = font == CanvasTextFont.BANGERS
                        ) {
                            font = CanvasTextFont.BANGERS
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Marker",
                            fontFamily = PermanentMarkerFontFamily,
                            selected = font == CanvasTextFont.PERMANENT_MARKER
                        ) {
                            font = CanvasTextFont.PERMANENT_MARKER
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Kalam",
                            fontFamily = KalamFontFamily,
                            selected = font == CanvasTextFont.KALAM
                        ) {
                            font = CanvasTextFont.KALAM
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Caveat",
                            fontFamily = CaveatFontFamily,
                            selected = font == CanvasTextFont.CAVEAT
                        ) {
                            font = CanvasTextFont.CAVEAT
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Merri",
                            fontFamily = MerriweatherFontFamily,
                            selected = font == CanvasTextFont.MERRIWEATHER
                        ) {
                            font = CanvasTextFont.MERRIWEATHER
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Oswald",
                            fontFamily = OswaldFontFamily,
                            selected = font == CanvasTextFont.OSWALD
                        ) {
                            font = CanvasTextFont.OSWALD
                            focusRequester.requestFocus()
                        }
                        FontOption(
                            label = "Baloo 2",
                            fontFamily = Baloo2FontFamily,
                            selected = font == CanvasTextFont.BALOO_2
                        ) {
                            font = CanvasTextFont.BALOO_2
                            focusRequester.requestFocus()
                        }
                    }
                }

                TextEditorPanel.SIZE -> {
                    EditorTertiaryBar {
                        Text(
                            text = "Aa",
                            color = Color.White,
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        androidx.compose.material3.Slider(
                            value = scale,
                            onValueChange = { scale = it },
                            valueRange = 0.3f..6f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "AA",
                            color = Color.White,
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
                                rotationDeg = value.coerceIn(ROTATION_MIN_DEG, ROTATION_MAX_DEG)
                                focusRequester.requestFocus()
                            },
                            valueRange = ROTATION_MIN_DEG..ROTATION_MAX_DEG,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                TextEditorPanel.COLOR -> {
                    val scroll = rememberScrollState()
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
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        colorArgb = candidate
                                        focusRequester.requestFocus()
                                    }
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
            contentDescription = "Color",
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
            selected = false
        ) { onCycleAlignment() }

        Spacer(modifier = Modifier.weight(1f))

        EditorIconButton(
            iconRes = R.drawable.ic_text_toolbar_pill,
            contentDescription = "Background pill",
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
                indication = null
            ) { onClick() },
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

private fun hitTest(
    elements: List<CanvasElement>,
    stickerBitmaps: Map<String, android.graphics.Bitmap>,
    pointPx: Offset,
    canvasSize: IntSize,
    textSizePx: Float,
    poppins: Typeface,
    virgil: Typeface,
    dmSans: Typeface,
    spaceMono: Typeface,
    playfair: Typeface,
    bangers: Typeface,
    permanentMarker: Typeface,
    kalam: Typeface,
    caveat: Typeface,
    merriweather: Typeface,
    oswald: Typeface,
    baloo2: Typeface
): String? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
    val paint = TextPaint().apply { isAntiAlias = true }
    paint.textSize = textSizePx

    val reversed = elements.asReversed()
    for (element in reversed) {
        val centerPx = element.center.denormalize(canvasSize)
        val dx = pointPx.x - centerPx.x
        val dy = pointPx.y - centerPx.y
        val c = cos(element.rotationRad.toDouble()).toFloat()
        val s = sin(element.rotationRad.toDouble()).toFloat()
        val xRot = (c * dx) + (s * dy)
        val yRot = (-s * dx) + (c * dy)

        when (element) {
            is CanvasTextElement -> {
                val wrapWidth = (element.boxWidth * canvasSize.width).toInt().coerceAtLeast(1)
                paint.typeface = when (element.font) {
                    CanvasTextFont.POPPINS -> poppins
                    CanvasTextFont.VIRGIL -> virgil
                    CanvasTextFont.DM_SANS -> dmSans
                    CanvasTextFont.SPACE_MONO -> spaceMono
                    CanvasTextFont.PLAYFAIR_DISPLAY -> playfair
                    CanvasTextFont.BANGERS -> bangers
                    CanvasTextFont.PERMANENT_MARKER -> permanentMarker
                    CanvasTextFont.KALAM -> kalam
                    CanvasTextFont.CAVEAT -> caveat
                    CanvasTextFont.MERRIWEATHER -> merriweather
                    CanvasTextFont.OSWALD -> oswald
                    CanvasTextFont.BALOO_2 -> baloo2
                }
                val alignment = when (element.alignment) {
                    CanvasTextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                    CanvasTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                    CanvasTextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                }
                val layout = StaticLayout.Builder.obtain(element.text, 0, element.text.length, paint, wrapWidth)
                    .setAlignment(alignment)
                    .setIncludePad(false)
                    .build()
                val halfW = layout.width / 2f
                val halfH = layout.height / 2f
                val xLocal = xRot / element.scale
                val yLocal = yRot / element.scale
                if (xLocal in -halfW..halfW && yLocal in -halfH..halfH) {
                    return element.id
                }
            }
            is CanvasStickerElement -> {
                val key = "${element.packKey}:${element.packVersion}:${element.stickerId}:full"
                val bitmap = stickerBitmaps[key]
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
                val xLocal = xRot
                val yLocal = yRot
                if (xLocal in -halfW..halfW && yLocal in -halfH..halfH) {
                    return element.id
                }
            }
        }
    }
    return null
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
