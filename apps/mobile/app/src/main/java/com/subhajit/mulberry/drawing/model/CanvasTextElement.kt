package com.subhajit.mulberry.drawing.model

sealed interface CanvasElement {
    val kind: CanvasElementKind
    val id: String
    val createdAt: Long
    val center: StrokePoint
    val rotationRad: Float
    val scale: Float
}

enum class CanvasElementKind {
    TEXT,
    STICKER
}

data class CanvasTextElement(
    override val id: String,
    val text: String,
    override val createdAt: Long,
    override val center: StrokePoint,
    override val rotationRad: Float = 0f,
    override val scale: Float = 1f,
    val boxWidth: Float,
    val colorArgb: Long,
    val backgroundPillEnabled: Boolean = false,
    val font: CanvasTextFont = CanvasTextFont.POPPINS,
    val alignment: CanvasTextAlign = CanvasTextAlign.CENTER
) : CanvasElement {
    override val kind: CanvasElementKind = CanvasElementKind.TEXT
}

enum class CanvasTextFont {
    POPPINS,
    VIRGIL,
    DM_SANS,
    SPACE_MONO,
    PLAYFAIR_DISPLAY,
    BANGERS,
    PERMANENT_MARKER,
    KALAM,
    CAVEAT,
    MERRIWEATHER,
    OSWALD,
    BALOO_2
}

enum class CanvasTextAlign {
    LEFT,
    CENTER,
    RIGHT
}

data class CanvasStickerElement(
    override val id: String,
    override val createdAt: Long,
    override val center: StrokePoint,
    override val rotationRad: Float = 0f,
    override val scale: Float = 0.22f,
    val packKey: String,
    val packVersion: Int,
    val stickerId: String
) : CanvasElement {
    override val kind: CanvasElementKind = CanvasElementKind.STICKER
}
