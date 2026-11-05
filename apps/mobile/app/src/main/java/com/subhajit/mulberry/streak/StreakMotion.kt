package com.subhajit.mulberry.streak

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The streak screen's small motions, matched to the iPhone's: brief, played once, and reduced to
 * fades when the system's animations are off.
 */
internal object StreakMotion {
    /** From the screen opening to today's moment: the day filling in, or its ring calling. */
    const val TODAY_MOMENT_MS = 850L

    /** From the screen opening to the light crossing the bolt, as the bolt settles. */
    const val GLINT_MS = 250L

    /** The week's days arrive left to right, this far apart. */
    const val DAY_STAGGER_MS = 35L
}

/**
 * A spring described as SwiftUI describes one, by how long it takes and how far it bounces, so
 * the two apps move alike.
 */
internal fun appleSpring(durationSeconds: Float, bounce: Float = 0f): SpringSpec<Float> {
    val angular = 2f * PI.toFloat() / durationSeconds
    return spring(dampingRatio = 1f - bounce, stiffness = angular * angular)
}

/** How far an entrance has come: 0 before it starts, 1 settled, a little past 1 while it bounces. */
@Composable
internal fun rememberEntrance(
    arrived: Boolean,
    delayMs: Long,
    spec: AnimationSpec<Float>,
    reduceMotion: Boolean
): Animatable<Float, AnimationVector1D> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(arrived, reduceMotion) {
        if (!arrived) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        if (reduceMotion) {
            progress.animateTo(1f, tween(durationMillis = 200))
            return@LaunchedEffect
        }
        delay(delayMs)
        progress.animateTo(1f, spec)
    }
    return progress
}

/**
 * Settles into place as [progress] reaches 1: up from [rise] lower, from [fromScale], out of a
 * blur of [blur] where the platform can blur (Android 12 and later). With motion reduced it only
 * fades in.
 */
internal fun Modifier.streakEntrance(
    progress: Animatable<Float, AnimationVector1D>,
    reduceMotion: Boolean,
    rise: Dp = 0.dp,
    fromScale: Float = 1f,
    blur: Dp = 0.dp
): Modifier = graphicsLayer {
    val value = progress.value
    alpha = value.coerceIn(0f, 1f)
    if (reduceMotion) return@graphicsLayer
    val remaining = 1f - value
    translationY = rise.toPx() * remaining
    val scale = fromScale + (1f - fromScale) * value
    scaleX = scale
    scaleY = scale
    if (blur > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val radius = blur.toPx() * remaining.coerceIn(0f, 1f)
        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius) else null
    }
}

/** A band of light that crosses the bolt once each time [play] runs, on the bolt's own pixels. */
@Stable
internal class BoltGlintState {
    /** Where the band is, as a fraction of the bolt's width from its centre; it rests out of sight. */
    val travel = Animatable(REST)

    suspend fun play() {
        travel.snapTo(REST)
        travel.animateTo(-REST, tween(durationMillis = 950, easing = FastOutSlowInEasing))
        travel.snapTo(REST)
    }

    private companion object {
        const val REST = -0.8f
    }
}

internal fun Modifier.boltGlint(state: BoltGlintState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val travel = state.travel.value
            val angle = 20f * PI.toFloat() / 180f
            val direction = Offset(cos(angle), sin(angle))
            val halfBand = size.width * 0.22f / 2f
            val centre = Offset(size.width * (0.5f + travel), size.height / 2f)
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0f),
                    0.5f to Color.White.copy(alpha = 0.42f),
                    1f to Color.White.copy(alpha = 0f),
                    start = centre - direction * halfBand,
                    end = centre + direction * halfBand
                ),
                // Only where the bolt already is: the light never spills past its outline.
                blendMode = BlendMode.SrcAtop
            )
        }

/** The bolt squashing when pressed, then springing back. */
@Stable
internal class SquishState {
    val scaleX = Animatable(1f)
    val scaleY = Animatable(1f)

    suspend fun play() = coroutineScope {
        launch {
            scaleX.animateTo(1.06f, tween(durationMillis = 90))
            scaleX.animateTo(1f, appleSpring(0.45f, 0.45f))
        }
        launch {
            scaleY.animateTo(0.9f, tween(durationMillis = 90))
            scaleY.animateTo(1f, appleSpring(0.45f, 0.45f))
        }
    }
}

internal fun Modifier.squish(state: SquishState): Modifier = graphicsLayer {
    scaleX = state.scaleX.value
    scaleY = state.scaleY.value
}

/** A day swelling as it fills in. */
internal suspend fun Animatable<Float, AnimationVector1D>.dayPop() {
    animateTo(1.22f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
    animateTo(1f, appleSpring(0.45f, 0.45f))
}

/** The toolbar bolt hopping as it takes the streak back. */
internal suspend fun Animatable<Float, AnimationVector1D>.boltHop(delayMs: Long = 300L) {
    delay(delayMs)
    animateTo(1.28f, tween(durationMillis = 140, easing = FastOutSlowInEasing))
    animateTo(1f, appleSpring(0.4f, 0.5f))
}

/** A ring rippling out once from a day still to do; [progress] runs from 0 to 1. */
internal fun Modifier.dayRipple(progress: Animatable<Float, AnimationVector1D>, color: Color): Modifier =
    drawBehind {
        val value = progress.value
        if (value <= 0f || value >= 1f) return@drawBehind
        val stroke = 2.dp.toPx()
        drawCircle(
            color = color,
            radius = (size.minDimension / 2f - stroke / 2f) * (1f + 0.75f * value),
            alpha = 0.9f * (1f - value),
            style = Stroke(width = stroke)
        )
    }

internal suspend fun Animatable<Float, AnimationVector1D>.ripple() {
    snapTo(0f)
    animateTo(1f, tween(durationMillis = 950, easing = FastOutSlowInEasing))
}

/** A number that rolls up to its new value, as the iPhone's counting text does. */
@Composable
internal fun RollingNumber(
    value: Int,
    reduceMotion: Boolean,
    content: @Composable (Int) -> Unit
) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(250)) togetherWith fadeOut(tween(250))
            } else {
                val up = targetState > initialState
                (slideInVertically(tween(400, easing = FastOutSlowInEasing)) { if (up) it else -it } + fadeIn(tween(250)))
                    .togetherWith(
                        slideOutVertically(tween(400, easing = FastOutSlowInEasing)) { if (up) -it else it } +
                            fadeOut(tween(200))
                    )
                    .using(SizeTransform(clip = true))
            }
        },
        label = "streakRollingNumber"
    ) { number -> content(number) }
}
