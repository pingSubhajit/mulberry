package com.subhajit.mulberry.reactions

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ReactionGif(
    type: ReactionType,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val assetPackGeneration = rememberReactionAssetPackGeneration()
    var decodeAttempt by remember(type) { mutableIntStateOf(0) }
    val movie = remember(type, decodeAttempt, assetPackGeneration) {
        ReactionGifAssets.decodeMovie(context, type)
    }
    var elapsedMs by remember(type) { mutableLongStateOf(0L) }
    val fallbackEmojiPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
    }

    LaunchedEffect(type) {
        if (type != ReactionType.HEART) {
            ReactionGifAssets.requestFastFollowPack(context)
        }
    }

    LaunchedEffect(movie, type, assetPackGeneration) {
        if (movie != null || type == ReactionType.HEART) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            decodeAttempt += 1
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

@Composable
private fun rememberReactionAssetPackGeneration(): Int {
    val context = LocalContext.current.applicationContext
    val assetPackManager = remember(context) { createReactionAssetPackManager(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var generation by remember(assetPackManager) { mutableIntStateOf(0) }

    DisposableEffect(assetPackManager) {
        if (assetPackManager == null) return@DisposableEffect onDispose { }
        val listener = AssetPackStateUpdateListener { state ->
            if (
                state.name() == REACTION_ASSET_PACK &&
                state.status() in setOf(
                    AssetPackStatus.COMPLETED,
                    AssetPackStatus.FAILED,
                    AssetPackStatus.CANCELED,
                    AssetPackStatus.NOT_INSTALLED
                )
            ) {
                ReactionGifAssets.onFastFollowPackStatus(state.status())
                mainHandler.post {
                    generation += 1
                }
            }
        }
        assetPackManager.registerListener(listener)
        onDispose { assetPackManager.unregisterListener(listener) }
    }

    return generation
}
