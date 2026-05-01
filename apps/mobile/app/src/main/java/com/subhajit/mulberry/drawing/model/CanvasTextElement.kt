package com.subhajit.mulberry.drawing.model

data class CanvasTextElement(
    val id: String,
    val text: String,
    val createdAt: Long,
    val center: StrokePoint,
    val rotationRad: Float = 0f,
    val scale: Float = 1f,
    val boxWidth: Float,
    val colorArgb: Long,
    val backgroundPillEnabled: Boolean = false,
    val font: CanvasTextFont = CanvasTextFont.POPPINS,
    val alignment: CanvasTextAlign = CanvasTextAlign.CENTER
)

enum class CanvasTextFont {
    POPPINS,
    VIRGIL
}

enum class CanvasTextAlign {
    LEFT,
    CENTER,
    RIGHT
}

