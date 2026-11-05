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
    STICKER,
    PHOTO
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
    OSWALD
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

/** How a photo sits on the canvas. */
enum class CanvasPhotoStyle {
    /** A thin white border. */
    FRAME,
    /** A white border with a deeper bottom edge. */
    POLAROID,
    /** The subject cut out, with a white outline. */
    STICKER;

    companion object {
        /** Anything this build doesn't know reads as [FRAME], as on iOS. */
        fun fromWire(value: String?): CanvasPhotoStyle =
            entries.firstOrNull { it.name == value } ?: FRAME
    }
}

/**
 * A photo placed on the canvas. [scale] is the framed photo's long edge as a fraction of the canvas
 * width. The sticker style draws [cutoutPhotoId] when there is one.
 */
data class CanvasPhotoElement(
    override val id: String,
    override val createdAt: Long,
    override val center: StrokePoint,
    override val rotationRad: Float = 0f,
    override val scale: Float = DEFAULT_SCALE,
    val photoId: String,
    val cutoutPhotoId: String? = null,
    /** The original photo's width over its height. */
    val aspectRatio: Float,
    val style: CanvasPhotoStyle = CanvasPhotoStyle.FRAME
) : CanvasElement {
    override val kind: CanvasElementKind = CanvasElementKind.PHOTO

    val renderScale: Float
        get() = scale.coerceIn(SCALE_MIN, SCALE_MAX)

    /** The image the style draws: the cut-out for a sticker, when there is one. */
    val shownPhotoId: String
        get() = if (style == CanvasPhotoStyle.STICKER) cutoutPhotoId ?: photoId else photoId

    companion object {
        const val SCALE_MIN = 0.12f
        const val SCALE_MAX = 1.2f
        const val DEFAULT_SCALE = 0.5f
    }
}
