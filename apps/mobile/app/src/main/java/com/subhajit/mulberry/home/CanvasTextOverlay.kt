package com.subhajit.mulberry.home

import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.subhajit.mulberry.R
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.VirgilFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.math.PI
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
fun CanvasTextOverlay(
    elements: List<CanvasTextElement>,
    activeTool: DrawingTool,
    palette: List<Long>,
    selectedColorArgb: Long,
    onAddElement: (CanvasTextElement) -> Unit,
    onUpdateElement: (CanvasTextElement) -> Unit,
    onDeleteElement: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var selectedElementId by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<TextEditorState?>(null) }
    var liveTransformPreview by remember { mutableStateOf<CanvasTextElement?>(null) }

    LaunchedEffect(activeTool) {
        if (activeTool != DrawingTool.TEXT) {
            selectedElementId = null
            editor = null
            liveTransformPreview = null
        }
    }

    val poppinsTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.poppins_regular) ?: Typeface.DEFAULT }
    val virgilTypeface = remember(context) { ResourcesCompat.getFont(context, R.font.virgil_regular) ?: Typeface.DEFAULT }
    val baseTextSizePx = with(density) { 34.sp.toPx() }

    // Important: only install pointer handlers when TEXT tool is active, otherwise this
    // overlay sits above the drawing canvas and blocks all drawing gestures.
    val tapModifier = if (activeTool == DrawingTool.TEXT) {
        Modifier.pointerInput(elements, selectedElementId, canvasSize, selectedColorArgb) {
            detectTapGestures(
                onTap = { offset ->
                    if (canvasSize.width > 0 && canvasSize.height > 0) {
                        val hitId = hitTest(
                            elements,
                            offset,
                            canvasSize,
                            baseTextSizePx,
                            poppinsTypeface,
                            virgilTypeface
                        )
                        if (hitId != null) {
                            selectedElementId = hitId
                        } else {
                            val id = UUID.randomUUID().toString()
                            val element = CanvasTextElement(
                                id = id,
                                text = "",
                                createdAt = System.currentTimeMillis(),
                                center = offset.toNormalizedPoint(canvasSize),
                                rotationRad = 0f,
                                scale = 1f,
                                boxWidth = 0.7f,
                                colorArgb = selectedColorArgb,
                                backgroundPillEnabled = false,
                                font = CanvasTextFont.POPPINS,
                                alignment = CanvasTextAlign.CENTER
                            )
                            onAddElement(element)
                            selectedElementId = id
                            editor = TextEditorState(elementId = id, initialText = "", isNew = true)
                        }
                    }
                },
                onDoubleTap = { offset ->
                    if (canvasSize.width > 0 && canvasSize.height > 0) {
                        val hitId = hitTest(
                            elements,
                            offset,
                            canvasSize,
                            baseTextSizePx,
                            poppinsTypeface,
                            virgilTypeface
                        )
                        if (hitId != null) {
                            val target = elements.firstOrNull { it.id == hitId }
                            if (target != null) {
                                selectedElementId = hitId
                                editor = TextEditorState(
                                    elementId = hitId,
                                    initialText = target.text,
                                    isNew = false
                                )
                            }
                        }
                    }
                }
            )
        }
    } else {
        Modifier
    }

    val transformState = rememberTransformableState { zoomChange, panChange, rotationDegrees ->
        if (activeTool != DrawingTool.TEXT) return@rememberTransformableState
        if (editor != null) return@rememberTransformableState
        if (canvasSize.width <= 0 || canvasSize.height <= 0) return@rememberTransformableState
        val selectedId = selectedElementId ?: return@rememberTransformableState
        val current = liveTransformPreview ?: elements.firstOrNull { it.id == selectedId }
            ?: return@rememberTransformableState

        val centerPx = current.center.denormalize(canvasSize)
        val nextCenterPx = centerPx + panChange
        val nextScale = (current.scale * zoomChange).coerceIn(0.3f, 6f)
        val nextRotation = current.rotationRad + rotationDegrees.degreesToRadians()
        liveTransformPreview = current.copy(
            center = nextCenterPx.toNormalizedPoint(canvasSize),
            scale = nextScale,
            rotationRad = nextRotation
        )
    }

    LaunchedEffect(transformState.isTransformInProgress, selectedElementId, elements) {
        if (!transformState.isTransformInProgress) {
            val selectedId = selectedElementId ?: return@LaunchedEffect
            val base = elements.firstOrNull { it.id == selectedId } ?: return@LaunchedEffect
            val preview = liveTransformPreview ?: return@LaunchedEffect
            liveTransformPreview = null
            if (preview != base) {
                onUpdateElement(preview)
            }
        }
    }

    val transformModifier = if (activeTool == DrawingTool.TEXT) {
        Modifier.transformable(
            state = transformState,
            enabled = selectedElementId != null && editor == null
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .then(tapModifier)
            .then(transformModifier)
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

                    if (activeTool == DrawingTool.TEXT && element.id == selectedElementId) {
                        val outlinePaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            style = android.graphics.Paint.Style.STROKE
                            strokeWidth = with(density) { 2.dp.toPx() }
                            color = 0x66FFFFFF
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

        val selectedId = selectedElementId
        if (activeTool == DrawingTool.TEXT && selectedId != null && editor == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(44.dp)
                    .clickable {
                        onDeleteElement(selectedId)
                        selectedElementId = null
                    },
                color = MaterialTheme.mulberryAppColors.softSurfaceStrong,
                shape = CircleShape
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text(
                        text = "Del",
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    val editorState = editor
    if (editorState != null) {
        val element = elements.firstOrNull { it.id == editorState.elementId }
        if (element != null) {
            TextEditorOverlay(
                element = element,
                palette = palette,
                autoFocus = editorState.isNew,
                onDismiss = {
                    if (editorState.isNew && element.text.isBlank()) {
                        onDeleteElement(element.id)
                    }
                    editor = null
                },
                onDone = { updated ->
                    if (editorState.isNew && updated.text.isBlank()) {
                        onDeleteElement(updated.id)
                    } else {
                        onUpdateElement(updated)
                    }
                    editor = null
                }
            )
        } else {
            editor = null
        }
    }
}

private data class TextEditorState(
    val elementId: String,
    val initialText: String,
    val isNew: Boolean
)

@Composable
private fun TextEditorOverlay(
    element: CanvasTextElement,
    palette: List<Long>,
    autoFocus: Boolean,
    onDismiss: () -> Unit,
    onDone: (CanvasTextElement) -> Unit
) {
    var textField by remember(element.id) { mutableStateOf(TextFieldValue(element.text)) }
    var font by remember(element.id) { mutableStateOf(element.font) }
    var alignment by remember(element.id) { mutableStateOf(element.alignment) }
    var backgroundPillEnabled by remember(element.id) { mutableStateOf(element.backgroundPillEnabled) }
    var colorArgb by remember(element.id) { mutableStateOf(element.colorArgb) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(element.id, autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.mulberryAppColors.softSurface
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Text(
                        text = "Edit text",
                        fontFamily = PoppinsFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            onDone(
                                element.copy(
                                    text = textField.text,
                                    font = font,
                                    alignment = alignment,
                                    backgroundPillEnabled = backgroundPillEnabled,
                                    colorArgb = colorArgb
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E7C59))
                    ) {
                        androidx.compose.material3.Text(
                            text = "Done",
                            color = Color.White,
                            fontFamily = PoppinsFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                androidx.compose.foundation.text.BasicTextField(
                    value = textField,
                    onValueChange = { textField = it },
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = when (font) {
                            CanvasTextFont.POPPINS -> PoppinsFontFamily
                            CanvasTextFont.VIRGIL -> VirgilFontFamily
                        },
                        fontSize = 26.sp,
                        textAlign = when (alignment) {
                            CanvasTextAlign.LEFT -> TextAlign.Left
                            CanvasTextAlign.CENTER -> TextAlign.Center
                            CanvasTextAlign.RIGHT -> TextAlign.Right
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(MaterialTheme.mulberryAppColors.softSurfaceAlt, RoundedCornerShape(14.dp))
                        .padding(12.dp)
                        .focusRequester(focusRequester)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TogglePill(
                        label = "Poppins",
                        selected = font == CanvasTextFont.POPPINS
                    ) { font = CanvasTextFont.POPPINS }
                    TogglePill(
                        label = "Virgil",
                        selected = font == CanvasTextFont.VIRGIL
                    ) { font = CanvasTextFont.VIRGIL }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TogglePill(
                        label = "Left",
                        selected = alignment == CanvasTextAlign.LEFT
                    ) { alignment = CanvasTextAlign.LEFT }
                    TogglePill(
                        label = "Center",
                        selected = alignment == CanvasTextAlign.CENTER
                    ) { alignment = CanvasTextAlign.CENTER }
                    TogglePill(
                        label = "Right",
                        selected = alignment == CanvasTextAlign.RIGHT
                    ) { alignment = CanvasTextAlign.RIGHT }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TogglePill(
                        label = "Pill",
                        selected = backgroundPillEnabled
                    ) { backgroundPillEnabled = !backgroundPillEnabled }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    palette.forEach { candidate ->
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color(candidate), RoundedCornerShape(999.dp))
                                .alpha(if (candidate == colorArgb) 1f else 0.55f)
                                .clickable { colorArgb = candidate }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TogglePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) Color(0xFF0E7C59) else MaterialTheme.mulberryAppColors.softSurfaceAlt
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        androidx.compose.material3.Text(
            text = label,
            color = fg,
            fontFamily = PoppinsFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
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
