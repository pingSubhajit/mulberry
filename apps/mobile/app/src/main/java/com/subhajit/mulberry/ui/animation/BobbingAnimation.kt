package com.subhajit.mulberry.ui.animation

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberBobbingPhase(
    durationMillis: Int = 850,
    easing: Easing = FastOutSlowInEasing,
    label: String = "bobbing"
): Float {
    val phase by rememberInfiniteTransition(label = label).animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = easing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}_phase"
    )
    return phase
}

@Composable
fun rememberBobbingOffsetDp(
    amplitude: Dp = 7.dp,
    durationMillis: Int = 850,
    easing: Easing = FastOutSlowInEasing,
    label: String = "bobbing"
): Dp {
    val phase = rememberBobbingPhase(
        durationMillis = durationMillis,
        easing = easing,
        label = label
    )
    return -(amplitude * phase)
}

