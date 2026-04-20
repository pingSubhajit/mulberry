package com.subhajit.elaris.drawing.model

data class CanvasState(
    val strokes: List<Stroke> = emptyList(),
    val activeStroke: Stroke? = null,
    val isEmpty: Boolean = true,
    val revision: Long = 0L,
    val snapshotState: CanvasSnapshotState = CanvasSnapshotState()
)
