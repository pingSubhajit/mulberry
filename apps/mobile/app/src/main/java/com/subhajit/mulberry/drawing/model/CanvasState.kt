package com.subhajit.mulberry.drawing.model

data class CanvasState(
    val strokes: List<Stroke> = emptyList(),
    val elements: List<CanvasElement> = emptyList(),
    val activeStroke: Stroke? = null,
    val remoteActiveStrokes: List<Stroke> = emptyList(),
    val isEmpty: Boolean = true,
    val revision: Long = 0L,
    val snapshotState: CanvasSnapshotState = CanvasSnapshotState()
) {
    val textElements: List<CanvasTextElement>
        get() = elements.filterIsInstance<CanvasTextElement>()

    val stickerElements: List<CanvasStickerElement>
        get() = elements.filterIsInstance<CanvasStickerElement>()
}
