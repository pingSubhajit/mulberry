package com.subhajit.mulberry.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.mulberryTapScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f
): Modifier = composed {
    if (!enabled) return@composed this

    var pressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "mulberryTapScale"
    )

    // Read pointer state without consuming events so Material components still handle ripples/press.
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }
}
