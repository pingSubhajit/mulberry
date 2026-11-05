package com.subhajit.mulberry.ui.animation

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A gentle up-and-down bob's phase, from 0 to 1 and back, forever. It rests at 0 with the system's
 * animations removed.
 *
 * Read it only while placing or drawing, as [bobbing] does, never while composing: a value read
 * in composition rebuilds the whole composable every frame for as long as the bob runs. Call this
 * only where the bobbing thing is shown, so the bob stops when it goes away.
 */
@Composable
fun rememberBobbingPhase(
    durationMillis: Int = 850,
    easing: Easing = FastOutSlowInEasing,
    label: String = "bobbing"
): State<Float> {
    if (!rememberSystemAnimationsEnabled()) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = label).animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMillis, easing = easing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "${label}_phase"
    )
}

/** Lifts what it modifies by up to [amplitude] as [phase] runs, re-placing it without recomposing. */
fun Modifier.bobbing(phase: State<Float>, amplitude: Dp = 7.dp): Modifier =
    offset { IntOffset(x = 0, y = -(amplitude.toPx() * phase.value).roundToInt()) }
