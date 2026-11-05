package com.subhajit.mulberry.drawing.model

/**
 * The canvas's layers, bottom to top, the same on every client:
 * stickers and photos in one stack, then text, then strokes over every element.
 */
enum class CanvasPaintLayer {
    /** Stickers and photos: one mixed stack. */
    MEDIA,
    TEXT,
    STROKES
}

val CanvasElement.paintLayer: CanvasPaintLayer
    get() = when (this) {
        is CanvasStickerElement, is CanvasPhotoElement -> CanvasPaintLayer.MEDIA
        is CanvasTextElement -> CanvasPaintLayer.TEXT
    }

/**
 * Elements bottom to top. Each layer keeps the list's own order, where the element added or
 * changed most recently is last, so it stays on top of its layer.
 */
fun <T : CanvasElement> List<T>.inPaintOrder(): List<T> =
    sortedBy { element -> element.paintLayer.ordinal }

/** Elements top to bottom: the order a finger finds them in. */
fun <T : CanvasElement> List<T>.inHitTestOrder(): List<T> = inPaintOrder().asReversed()

/**
 * The topmost element [isUnder] the finger: text before stickers, and within each the one changed
 * last. A tool that picks up only its own kind passes only those elements.
 */
fun <T : CanvasElement> List<T>.topmostElement(isUnder: (T) -> Boolean): T? =
    inHitTestOrder().firstOrNull(isUnder)

/** One thing the canvas draws. */
sealed interface CanvasPaintItem {
    val layer: CanvasPaintLayer

    data class Element(val element: CanvasElement) : CanvasPaintItem {
        override val layer: CanvasPaintLayer
            get() = element.paintLayer
    }

    /** A finished stroke, or one still being drawn. */
    data class StrokeItem(val stroke: Stroke, val isLive: Boolean) : CanvasPaintItem {
        override val layer: CanvasPaintLayer
            get() = CanvasPaintLayer.STROKES
    }
}

/**
 * Everything on the canvas, bottom to top. [committedStrokes] are in the order they were finished;
 * strokes still being drawn go over them, this device's own last.
 */
fun canvasPaintOrder(
    elements: List<CanvasElement>,
    committedStrokes: List<Stroke>,
    remoteActiveStrokes: List<Stroke> = emptyList(),
    localActiveStroke: Stroke? = null
): List<CanvasPaintItem> = buildList {
    elements.inPaintOrder().forEach { element -> add(CanvasPaintItem.Element(element)) }
    committedStrokes.forEach { stroke -> add(CanvasPaintItem.StrokeItem(stroke, isLive = false)) }
    remoteActiveStrokes.forEach { stroke -> add(CanvasPaintItem.StrokeItem(stroke, isLive = true)) }
    localActiveStroke?.let { stroke -> add(CanvasPaintItem.StrokeItem(stroke, isLive = true)) }
}

fun CanvasState.paintOrder(): List<CanvasPaintItem> = canvasPaintOrder(
    elements = elements,
    committedStrokes = strokes,
    remoteActiveStrokes = remoteActiveStrokes,
    localActiveStroke = activeStroke
)
