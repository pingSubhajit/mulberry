package com.subhajit.mulberry.reactions

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        AnimatedReactionImage(type = type, modifier = modifier)
    } else {
        MovieReactionGif(type = type, modifier = modifier)
    }
}

/**
 * Android 9 and later: the platform's animated drawable, which decodes frames on its own thread
 * and draws them on the GPU, so an open rail of six reactions costs the main thread next to nothing.
 */
@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun AnimatedReactionImage(
    type: ReactionType,
    modifier: Modifier
) {
    val context = LocalContext.current.applicationContext
    var sizePx by remember { mutableIntStateOf(0) }
    // The emoji stands in for the moment the decode takes.
    val drawable by produceState<Drawable?>(null, type, sizePx) {
        if (sizePx <= 0) return@produceState
        value = withContext(Dispatchers.IO) {
            ReactionGifAssets.decodeAnimatedDrawable(context, type, sizePx)
        }
    }

    DisposableEffect(drawable) {
        val animated = drawable as? AnimatedImageDrawable
        animated?.start()
        onDispose { animated?.stop() }
    }

    Box(
        modifier = modifier.onSizeChanged { size -> sizePx = maxOf(size.width, size.height) },
        contentAlignment = Alignment.Center
    ) {
        val current = drawable
        if (current == null) {
            EmojiFallback(type = type, modifier = Modifier.matchParentSize())
        } else {
            AndroidView(
                factory = { viewContext ->
                    ImageView(viewContext).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                },
                update = { view -> if (view.drawable !== current) view.setImageDrawable(current) },
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

@Composable
private fun EmojiFallback(type: ReactionType, modifier: Modifier) {
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
    }
    Canvas(modifier = modifier) {
        paint.textSize = (min(size.width, size.height) * 0.78f).coerceAtLeast(1f)
        val y = size.height / 2f - (paint.ascent() + paint.descent()) / 2f
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawText(type.emoji, size.width / 2f, y, paint) }
    }
}

/** Before Android 9: [Movie], decoded once in the background and drawn in software. */
@Composable
private fun MovieReactionGif(
    type: ReactionType,
    modifier: Modifier
) {
    val context = LocalContext.current.applicationContext
    // Decoded once and kept, off the main thread: the emoji stands in for the moment it takes.
    val movie by produceState(initialValue = ReactionGifUiCache.peek(type), type) {
        value = ReactionGifUiCache.movie(context, type)
    }
    var elapsedMs by remember(type) { mutableLongStateOf(0L) }
    val fallbackEmojiPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
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
        val gif = movie
        if (gif == null) {
            fallbackEmojiPaint.textSize = (min(size.width, size.height) * 0.78f).coerceAtLeast(1f)
            val x = size.width / 2f
            val y = size.height / 2f - (fallbackEmojiPaint.ascent() + fallbackEmojiPaint.descent()) / 2f
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(type.emoji, x, y, fallbackEmojiPaint)
            }
            return@Canvas
        }
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
