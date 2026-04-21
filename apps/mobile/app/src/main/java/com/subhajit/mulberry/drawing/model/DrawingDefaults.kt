package com.subhajit.mulberry.drawing.model

object DrawingDefaults {
    const val DEFAULT_COLOR_ARGB: Long = 0xFF141414L
    const val DEFAULT_WIDTH: Float = 10f
    const val MIN_WIDTH: Float = 4f
    const val MAX_WIDTH: Float = 28f
    const val STROKE_HIT_TOLERANCE: Float = 16f

    val palette: List<Long> = listOf(
        0xFF141414L,
        0xFF3A3A3AL,
        0xFFF7F4EFL,
        0xFFB31329L,
        0xFFE85072L,
        0xFFFF6A2AL,
        0xFFF5B83DL,
        0xFFFFE66DL,
        0xFF567A3AL,
        0xFF006B4FL,
        0xFF80D8B0L,
        0xFF5BB7E8L,
        0xFF2457D6L,
        0xFF3B2F8FL,
        0xFFA78BFAL,
        0xFF7A4A32L,
        0xFFB56A43L,
        0xFFD8A0A8L
    )
}
