package com.subhajit.mulberry.ui.animation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Angle
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.Spread
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import nl.dionsegijn.konfetti.core.models.Size
import java.util.concurrent.TimeUnit

@Composable
fun TwoSidedConfettiBurst(modifier: Modifier = Modifier) {
    val parties = remember {
        val colors = listOf(
            0xFFB31329.toInt(),
            0xFFFF4D6D.toInt(),
            0xFFFFB000.toInt(),
            0xFFFFE066.toInt(),
            0xFFFF7A3D.toInt(),
            0xFF00A878.toInt(),
            0xFF6C5CE7.toInt(),
            0xFFFFF4F5.toInt()
        )
        val sizes = listOf(Size(6), Size(9), Size(12))
        val shapes = listOf(Shape.Square, Shape.Circle, Shape.Rectangle(0.35f))
        listOf(
            Party(
                angle = Angle.RIGHT - 12,
                spread = Spread.WIDE,
                speed = 22f,
                maxSpeed = 44f,
                damping = 0.90f,
                size = sizes,
                shapes = shapes,
                colors = colors,
                timeToLive = 3_200L,
                position = Position.Relative(0.0, 0.42),
                emitter = Emitter(duration = 360, TimeUnit.MILLISECONDS).max(70)
            ),
            Party(
                angle = Angle.LEFT + 12,
                spread = Spread.WIDE,
                speed = 22f,
                maxSpeed = 44f,
                damping = 0.90f,
                size = sizes,
                shapes = shapes,
                colors = colors,
                timeToLive = 3_200L,
                position = Position.Relative(1.0, 0.42),
                emitter = Emitter(duration = 360, TimeUnit.MILLISECONDS).max(70)
            )
        )
    }

    KonfettiView(
        modifier = modifier,
        parties = parties
    )
}

