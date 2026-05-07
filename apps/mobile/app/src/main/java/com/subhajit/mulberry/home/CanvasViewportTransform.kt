package com.subhajit.mulberry.home

import androidx.compose.ui.geometry.Offset

data class CanvasViewportTransform(
    val scale: Float = 1f,
    val offsetPx: Offset = Offset.Zero
)

