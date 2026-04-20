package com.subhajit.elaris.drawing.model

object DrawingDefaults {
    const val DEFAULT_COLOR_ARGB: Long = 0xFFFF6F2CL
    const val DEFAULT_WIDTH: Float = 10f
    const val MIN_WIDTH: Float = 4f
    const val MAX_WIDTH: Float = 28f
    const val STROKE_HIT_TOLERANCE: Float = 16f

    val palette: List<Long> = listOf(
        0xFF1D1D1FL,
        0xFFFF6F2CL,
        0xFF0E7C59L,
        0xFF8A4B33L,
        0xFF26547CL
    )
}
