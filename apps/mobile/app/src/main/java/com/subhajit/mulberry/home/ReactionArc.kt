package com.subhajit.mulberry.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.subhajit.mulberry.core.ui.MulberryHaptic
import com.subhajit.mulberry.core.ui.rememberMulberryHaptics
import com.subhajit.mulberry.reactions.ReactionGif
import com.subhajit.mulberry.reactions.ReactionType
import com.subhajit.mulberry.ui.theme.mulberryAppColors
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Where a held finger opened the reactions, in the canvas pane's pixels. */
internal data class ReactionArcRequest(val tip: Offset)

/**
 * The held finger after the reactions opened: where it is, and how many times it has lifted, so
 * the arc can tell which reaction it slid onto and send it as the finger lets go.
 */
internal class ReactionArcPointer {
    var location by mutableStateOf<Offset?>(null)
    var lifts by mutableStateOf(0)
}

internal object ReactionArcMetrics {
    val Radius = 118.dp
    val Thickness = 64.dp
    val Reach = Radius + Thickness / 2

    /** How long a finger rests on the canvas before the reactions open, as on iPhone. */
    const val HOLD_MS = 500L
}

/**
 * Opens the reactions when a finger rests on the canvas, whatever the tool, as the iPhone does.
 * It watches touches before the canvas's own handlers ([PointerEventPass.Initial]); once the
 * finger has rested long enough, it claims the rest of that touch by consuming it, which the
 * drawing, erasing, element and eyedropper handlers take as their cue to let the touch go.
 */
internal fun Modifier.holdForReactions(
    enabled: () -> Boolean,
    onHold: (Offset) -> Unit,
    pointer: ReactionArcPointer
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        // The same distance that starts a stroke, so a finger either draws or holds, never both.
        val slop = viewConfiguration.touchSlop
        // Null when the finger rested the whole time: anything else means it drew, moved or lifted.
        val interrupted = withTimeoutOrNull(ReactionArcMetrics.HOLD_MS) {
            var resting = true
            while (resting) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                resting = change != null &&
                    change.pressed &&
                    event.changes.count { it.pressed } == 1 &&
                    (change.position - down.position).getDistance() <= slop
            }
            true
        }
        if (interrupted != null) return@awaitEachGesture

        onHold(down.position)
        pointer.location = null
        // The rest of this touch belongs to the reactions: it neither draws nor erases.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            event.changes.forEach { it.consume() }
            if (change == null || !change.pressed) {
                pointer.lifts += 1
                break
            }
            pointer.location = change.position
        }
    }
}

/**
 * The six reactions on an arc over the held finger, blooming out of the fingertip. Slide onto one
 * and let go to send it, or lift and tap one; a tap anywhere else puts them away. Opens under the
 * finger near the top of the screen.
 *
 * [tip] and [pointerOrigin] are in the pane's pixels: the finger's position from the pointer is
 * in the canvas's own pixels, so [pointerOrigin] is where the canvas starts in the pane.
 */
@Composable
internal fun ReactionArc(
    tip: Offset,
    paneWidthPx: Float,
    minTopPx: Float,
    pointer: ReactionArcPointer,
    pointerOrigin: Offset,
    reduceMotion: Boolean,
    onSend: (ReactionType) -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    val haptics = rememberMulberryHaptics()
    val radiusPx = with(density) { ReactionArcMetrics.Radius.toPx() }
    val thicknessPx = with(density) { ReactionArcMetrics.Thickness.toPx() }
    val reachPx = radiusPx + thicknessPx / 2f
    val itemSizePx = with(density) { 54.dp.toPx() }
    // Over the finger unless that would run under the top bar; kept on screen sideways.
    val below = tip.y - reachPx < minTopPx
    val centre = Offset(x = tip.x.coerceIn(reachPx, (paneWidthPx - reachPx).coerceAtLeast(reachPx)), y = tip.y)
    val types = ReactionType.entries
    // Left to right over the finger, turned 20° toward the left; mirrored when below.
    val angles = remember(below) {
        val start = if (below) 185.0 else 175.0
        val end = if (below) 35.0 else 325.0
        types.indices.map { index -> start + (end - start) * index / (types.size - 1) }
    }
    fun place(index: Int): Offset {
        val radians = Math.toRadians(angles[index])
        return Offset(
            x = centre.x + radiusPx * cos(radians).toFloat(),
            y = centre.y + radiusPx * sin(radians).toFloat()
        )
    }

    var hovered by remember { mutableStateOf<ReactionType?>(null) }
    val latestSend by rememberUpdatedState(onSend)
    // The reaction the sliding finger is over: the nearest place on the arc, if it's on the band.
    LaunchedEffect(pointer.location) {
        val location = pointer.location?.let { it + pointerOrigin }
        val nearest = location?.let { finger ->
            types.indices.minByOrNull { index -> (place(index) - finger).getDistance() }
                ?.takeIf { index -> (place(index) - finger).getDistance() < thicknessPx * 0.6f }
        }
        val next = nearest?.let { types[it] }
        if (next != null && next != hovered) haptics.perform(MulberryHaptic.Selection)
        hovered = next
    }
    // Letting go over a reaction sends it; letting go elsewhere leaves the arc open for a tap.
    var seenLifts by remember { mutableStateOf(pointer.lifts) }
    LaunchedEffect(pointer.lifts) {
        if (pointer.lifts == seenLifts) return@LaunchedEffect
        seenLifts = pointer.lifts
        hovered?.let(latestSend)
        hovered = null
    }

    val opened = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) opened.animateTo(1f, appleSpringFloat(0.24f, 0.15f))
    }
    val bandColor = MaterialTheme.mulberryAppColors.softSurfaceStrong

    Box(
        modifier = Modifier
            .fillMaxSize()
            // A tap anywhere else puts the reactions away without drawing.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .semantics {
                paneTitle = "Reactions"
                isTraversalGroup = true
            }
    ) {
        val sweepStart = angles.first().toFloat()
        val sweep = (angles.last() - angles.first()).toFloat()
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = opened.value
                    alpha = progress.coerceIn(0f, 1f)
                    val scale = 0.2f + 0.8f * progress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(
                        pivotFractionX = if (size.width > 0f) tip.x / size.width else 0.5f,
                        pivotFractionY = if (size.height > 0f) tip.y / size.height else 0.5f
                    )
                }
        ) {
            val topLeft = Offset(centre.x - radiusPx, centre.y - radiusPx)
            val arcSize = androidx.compose.ui.geometry.Size(radiusPx * 2f, radiusPx * 2f)
            // A soft edge under the band, as Material's raised surfaces have.
            drawArc(
                color = Color.Black.copy(alpha = 0.18f),
                startAngle = sweepStart,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft + Offset(0f, 3.dp.toPx()),
                size = arcSize,
                style = Stroke(width = thicknessPx + 4.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = bandColor,
                startAngle = sweepStart,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = thicknessPx, cap = StrokeCap.Round)
            )
        }

        types.forEachIndexed { index, type ->
            // Each flies out of the fingertip to its place on the arc, a beat after the one before.
            val flight = remember { Animatable(if (reduceMotion) 1f else 0f) }
            LaunchedEffect(Unit) {
                if (reduceMotion) return@LaunchedEffect
                delay(index * 14L)
                flight.animateTo(1f, appleSpringFloat(0.26f, 0.22f))
            }
            val lift by animateFloatAsState(
                targetValue = if (hovered == type) 1f else 0f,
                animationSpec = tween(durationMillis = 160),
                label = "reactionArcHover"
            )
            val target = place(index)
            val liftPx = with(density) { 8.dp.toPx() } * lift * (if (below) 1f else -1f)
            Box(
                modifier = Modifier
                    .offset {
                        val progress = flight.value
                        val x = tip.x + (target.x - tip.x) * progress - itemSizePx / 2f
                        val y = tip.y + (target.y - tip.y) * progress - itemSizePx / 2f + liftPx
                        IntOffset(x.roundToInt(), y.roundToInt())
                    }
                    .size(54.dp)
                    .graphicsLayer {
                        val progress = flight.value
                        val scale = (0.3f + 0.7f * progress) * (1f + 0.3f * lift)
                        scaleX = scale
                        scaleY = scale
                        alpha = progress.coerceIn(0f, 1f)
                    }
                    .clickable(role = Role.Button) { latestSend(type) }
                    .semantics { contentDescription = type.shortcutLabel }
            ) {
                ReactionGif(type = type, modifier = Modifier.size(42.dp).offset(6.dp, 6.dp))
            }
        }
    }
}

/** The arc's springs, as SwiftUI describes them: duration and bounce. */
private fun appleSpringFloat(durationSeconds: Float, bounce: Float) =
    com.subhajit.mulberry.streak.appleSpring(durationSeconds, bounce)
