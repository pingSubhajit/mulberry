package com.subhajit.mulberry.reactions

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ReactionGif(
    type: ReactionType,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var decodeAttempt by remember(type) { mutableLongStateOf(0L) }
    val movie = remember(type, decodeAttempt) { ReactionGifAssets.decodeMovie(context, type) }
    var elapsedMs by remember(type) { mutableLongStateOf(0L) }

    LaunchedEffect(movie, type, decodeAttempt) {
        if (movie != null || type == ReactionType.HEART) return@LaunchedEffect
        delay(1_000)
        decodeAttempt += 1
    }

    LaunchedEffect(movie, type) {
        if (movie == null) return@LaunchedEffect
        val startedAt = SystemClock.uptimeMillis()
        while (isActive) {
            elapsedMs = SystemClock.uptimeMillis() - startedAt
            delay(16)
        }
    }

    Canvas(modifier = modifier) {
        val gif = movie ?: return@Canvas
        val duration = gif.duration().takeIf { it > 0 } ?: 1_000
        gif.setTime((elapsedMs % duration).toInt())

        val scale = min(size.width / gif.width(), size.height / gif.height()).coerceAtLeast(0f)
        val left = (size.width - (gif.width() * scale)) / 2f
        val top = (size.height - (gif.height() * scale)) / 2f
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate(left, top)
            native.scale(scale, scale)
            gif.draw(native, 0f, 0f)
            native.restore()
        }
    }
}
