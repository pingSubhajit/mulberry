package com.subhajit.mulberry.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun Modifier.mulberrySelectionPop(
    selected: Boolean,
    enabled: Boolean = true,
    pressedScale: Float = 0.78f,
    overshootScale: Float = 1.10f,
    pressedRotationDegrees: Float = -14f,
    wiggleRotationDegrees: Float = 9f
): Modifier = composed {
    if (!enabled) return@composed this

    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    var previousSelected by remember { mutableStateOf(selected) }

    LaunchedEffect(selected) {
        val shouldPlay = selected && !previousSelected
        previousSelected = selected

        if (!selected) {
            scale.snapTo(1f)
            rotation.snapTo(0f)
            return@LaunchedEffect
        }

        if (!shouldPlay) return@LaunchedEffect

        coroutineScope {
            launch {
                scale.snapTo(1f)
                scale.animateTo(
                    targetValue = pressedScale,
                    animationSpec = tween(durationMillis = 90, easing = EaseIn)
                )
                scale.animateTo(
                    targetValue = overshootScale,
                    animationSpec = spring(dampingRatio = 0.42f, stiffness = 640f)
                )
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f)
                )
            }
            launch {
                rotation.snapTo(0f)
                rotation.animateTo(
                    targetValue = pressedRotationDegrees,
                    animationSpec = tween(durationMillis = 90, easing = EaseIn)
                )
                rotation.animateTo(
                    targetValue = wiggleRotationDegrees,
                    animationSpec = tween(durationMillis = 110, easing = LinearOutSlowInEasing)
                )
                rotation.animateTo(
                    targetValue = -wiggleRotationDegrees * 0.55f,
                    animationSpec = tween(durationMillis = 80, easing = FastOutSlowInEasing)
                )
                rotation.animateTo(
                    targetValue = wiggleRotationDegrees * 0.28f,
                    animationSpec = tween(durationMillis = 65, easing = FastOutSlowInEasing)
                )
                rotation.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.70f, stiffness = 560f)
                )
            }
        }
    }

    graphicsLayer {
        val currentScale = if (selected) scale.value else 1f
        scaleX = currentScale
        scaleY = currentScale
        rotationZ = if (selected) rotation.value else 0f
    }
}
