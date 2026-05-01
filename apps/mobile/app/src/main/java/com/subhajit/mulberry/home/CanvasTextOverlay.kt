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
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.VirgilFontFamily
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
    elements: List<CanvasTextElement>,
    activeTool: DrawingTool,
    palette: List<Long>,
    selectedColorArgb: Long,
    onAddElement: (CanvasTextElement) -> Unit,
    onUpdateElement: (CanvasTextElement) -> Unit,
    onDeleteElement: (String) -> Unit,
    onRequestEdit: (CanvasTextEditorSession) -> Unit,
    isEditorOpen: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedElementId by remember { mutableStateOf<String?>(null) }
    var liveTransformPreview by remember { mutableStateOf<CanvasTextElement?>(null) }

    LaunchedEffect(activeTool) {
        if (activeTool != DrawingTool.TEXT) {
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
    val baseTextSizePx = with(density) { 34.sp.toPx() }
    var isTransformInProgress by remember { mutableStateOf(false) }

    // Important: only install pointer handlers when needed; this overlay sits above the
    // drawing canvas so it must not block drawing/erase gestures unless it consumes them.
    val gestureModifier = when {
        activeTool == DrawingTool.TEXT && !isEditorOpen -> Modifier.pointerInput(elements, canvasSize, selectedColorArgb) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@awaitEachGesture

                val hitId = hitTest(
                    elements = elements,
                    pointPx = down.position,
                    canvasSize = canvasSize,
                    textSizePx = baseTextSizePx,
                    poppins = poppinsTypeface,
                    virgil = virgilTypeface
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

                fun activeElement(): CanvasTextElement? {
                    val selected = selectedElementId ?: return null
                    return liveTransformPreview ?: elements.firstOrNull { it.id == selected }
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
                        val nextScale = (current.scale * zoomChange).coerceIn(0.3f, 6f)
                        val nextRotation = current.rotationRad + rotationDelta
                        liveTransformPreview = current.copy(
                            center = nextCenterPx.toNormalizedPoint(canvasSize),
                            scale = nextScale,
                            rotationRad = nextRotation
                        )

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

                if (hitId == null) {
                    // Tap empty area => create + edit.
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
                    onAddElement(element)
                    selectedElementId = null
                    onRequestEdit(CanvasTextEditorSession(element = element, isNew = true))
                    return@awaitEachGesture
                }

                val base = elements.firstOrNull { it.id == hitId }
                val preview = liveTransformPreview
                liveTransformPreview = null

                if (beganDrag) {
                    if (preview != null && base != null && preview != base) {
                        onUpdateElement(preview)
                    }
                    selectedElementId = null
                } else {
                    // It's a tap on the element -> enter edit mode.
                    val target = base ?: return@awaitEachGesture
                    selectedElementId = null
                    onRequestEdit(CanvasTextEditorSession(element = target, isNew = false))
                }
            }
        }
        activeTool == DrawingTool.ERASE && !isEditorOpen -> Modifier.pointerInput(elements, canvasSize) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (canvasSize.width <= 0 || canvasSize.height <= 0) return@awaitEachGesture

                val hitId = hitTest(
                    elements = elements,
                    pointPx = down.position,
                    canvasSize = canvasSize,
                    textSizePx = baseTextSizePx,
                    poppins = poppinsTypeface,
                    virgil = virgilTypeface
                ) ?: return@awaitEachGesture

                // Consume so stroke-eraser doesn't also trigger.
                down.consume()

                // Wait until the pointer is released/cancelled.
                var pointer = down
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointer.id }
                        ?: event.changes.firstOrNull()
                        ?: break
                    pointer = change
                    if (!change.pressed) break
                    change.consume()
                }

                onDeleteElement(hitId)
            }
        }
        else -> Modifier
    }

    // All gestures are handled in a single pointerInput block (`gestureModifier`) so
    // tap-to-edit and drag-to-reposition work in the same gesture without requiring
    // a prior selection or recomposition.

    val boundsAlpha by animateFloatAsState(
        targetValue = if (
            activeTool == DrawingTool.TEXT &&
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
                    val center = element.center.denormalize(canvasSize)
                    val wrapWidth = (element.boxWidth * canvasSize.width).toInt().coerceAtLeast(1)
                    val typeface = when (element.font) {
                        CanvasTextFont.POPPINS -> poppinsTypeface
                        CanvasTextFont.VIRGIL -> virgilTypeface
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

                    // Only show bounds while the user is actively repositioning (dragging/pinching).
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
            }
        }

        // Delete is now handled inside the text edit toolbar (not on-canvas).
    }
}

private enum class TextEditorPanel {
    NONE,
    FONT,
    SIZE,
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
                scale = scale
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
                }.alpha(contentAlpha),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = textField,
                    onValueChange = { next -> textField = next },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    cursorBrush = SolidColor(textColor),
                    textStyle = TextStyle(
                        color = textColor,
                        fontFamily = when (font) {
                            CanvasTextFont.POPPINS -> PoppinsFontFamily
                            CanvasTextFont.VIRGIL -> VirgilFontFamily
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
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = contentScale
                                    scaleY = contentScale
                                }
                        ) {
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
                    EditorTertiaryBar {
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
            fontWeight = FontWeight.Medium
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
    elements: List<CanvasTextElement>,
    pointPx: Offset,
    canvasSize: IntSize,
    textSizePx: Float,
    poppins: Typeface,
    virgil: Typeface
): String? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
    val paint = TextPaint().apply { isAntiAlias = true }
    paint.textSize = textSizePx

    val reversed = elements.asReversed()
    for (element in reversed) {
        val wrapWidth = (element.boxWidth * canvasSize.width).toInt().coerceAtLeast(1)
        paint.typeface = when (element.font) {
            CanvasTextFont.POPPINS -> poppins
            CanvasTextFont.VIRGIL -> virgil
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
        val centerPx = element.center.denormalize(canvasSize)
        val dx = pointPx.x - centerPx.x
        val dy = pointPx.y - centerPx.y
        val c = cos(element.rotationRad.toDouble()).toFloat()
        val s = sin(element.rotationRad.toDouble()).toFloat()
        val xRot = (c * dx) + (s * dy)
        val yRot = (-s * dx) + (c * dy)
        val xLocal = xRot / element.scale
        val yLocal = yRot / element.scale
        if (xLocal in -halfW..halfW && yLocal in -halfH..halfH) {
            return element.id
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
