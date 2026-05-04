package com.subhajit.mulberry.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subhajit.mulberry.ui.theme.PoppinsFontFamily
import com.subhajit.mulberry.ui.theme.mulberryAppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RgbColorPickerSheet(
    visible: Boolean,
    initialColorArgb: Long,
    onDismissRequest: () -> Unit,
    onApply: (Long) -> Unit
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val initialHsv = remember(initialColorArgb) { initialColorArgb.toHsv() }
    var hue by remember(initialColorArgb) { mutableFloatStateOf(initialHsv.h) }
    var saturation by remember(initialColorArgb) { mutableFloatStateOf(initialHsv.s) }
    var value by remember(initialColorArgb) { mutableFloatStateOf(initialHsv.v) }

    LaunchedEffect(sheetState) {
        sheetState.expand()
    }

    val previewColorArgb = hsvToArgbLong(hue = hue, saturation = saturation, value = value)
    val previewColor = Color(previewColorArgb)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.mulberryAppColors.softSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 19.dp)
                    .size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .background(MaterialTheme.mulberryAppColors.dragHandle)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SaturationValueSquare(
                hue = hue,
                saturation = saturation,
                value = value,
                onChange = { nextS, nextV ->
                    saturation = nextS
                    value = nextV
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
            )
            HueBar(
                hue = hue,
                onHueChanged = { hue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text("Cancel", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.Medium)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = { onApply(previewColorArgb) },
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text("Done", fontFamily = PoppinsFontFamily, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SaturationValueSquare(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hueColor = Color(hsvToArgbLong(hue = hue, saturation = 1f, value = 1f))

    fun updateFromOffset(offset: Offset, width: Float, height: Float) {
        val s = (offset.x / width).coerceIn(0f, 1f)
        val v = (1f - (offset.y / height)).coerceIn(0f, 1f)
        onChange(s, v)
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.coerceAtLeast(1).toFloat()
                    val h = size.height.coerceAtLeast(1).toFloat()
                    updateFromOffset(pos, w, h)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val w = size.width.coerceAtLeast(1).toFloat()
                    val h = size.height.coerceAtLeast(1).toFloat()
                    updateFromOffset(change.position, w, h)
                    change.consume()
                }
            }
    ) {
        val cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Color.White, hueColor)),
            cornerRadius = cornerRadius
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
            cornerRadius = cornerRadius
        )

        val x = saturation.coerceIn(0f, 1f) * size.width
        val y = (1f - value.coerceIn(0f, 1f)) * size.height
        val center = Offset(x, y)

        drawCircle(
            color = Color.White,
            radius = 12.dp.toPx(),
            center = center,
            style = Stroke(width = 3.dp.toPx())
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.22f),
            radius = 12.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

@Composable
private fun HueBar(
    hue: Float,
    onHueChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    fun updateFromOffset(offsetX: Float, width: Float) {
        val t = (offsetX / width).coerceIn(0f, 1f)
        onHueChanged(t * 360f)
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val w = size.width.coerceAtLeast(1).toFloat()
                    updateFromOffset(pos.x, w)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val w = size.width.coerceAtLeast(1).toFloat()
                    updateFromOffset(change.position.x, w)
                    change.consume()
                }
            }
    ) {
        val rainbow = listOf(
            Color(0xFFFF3B30),
            Color(0xFFFFCC00),
            Color(0xFF34C759),
            Color(0xFF00C7BE),
            Color(0xFF007AFF),
            Color(0xFFAF52DE),
            Color(0xFFFF3B30)
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(rainbow),
            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        )

        val x = (hue.coerceIn(0f, 360f) / 360f) * size.width
        val center = Offset(x, size.height / 2f)
        drawCircle(
            color = Color.White,
            radius = 11.dp.toPx(),
            center = center,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.22f),
            radius = 11.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

private data class Hsv(val h: Float, val s: Float, val v: Float)

private fun Long.toHsv(): Hsv {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgbInt(), hsv)
    return Hsv(h = hsv[0], s = hsv[1], v = hsv[2])
}

private fun hsvToArgbLong(hue: Float, saturation: Float, value: Float): Long {
    val hsv = floatArrayOf(
        hue.coerceIn(0f, 360f),
        saturation.coerceIn(0f, 1f),
        value.coerceIn(0f, 1f)
    )
    val intColor = android.graphics.Color.HSVToColor(hsv)
    return intColor.toLong() and 0xFFFFFFFFL
}

private fun Long.toArgbInt(): Int = (this and 0xFFFFFFFFL).toInt()
