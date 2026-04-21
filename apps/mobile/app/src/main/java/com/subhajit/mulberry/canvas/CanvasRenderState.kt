package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.drawing.model.CanvasSnapshotState
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.ToolState
import com.subhajit.mulberry.sync.SyncState

data class CanvasRenderState(
    val committedStrokes: List<Stroke> = emptyList(),
    val localActiveStroke: Stroke? = null,
    val remoteActiveStrokes: Map<String, Stroke> = emptyMap(),
    val revision: Long = 0L,
    val snapshotState: CanvasSnapshotState = CanvasSnapshotState(),
    val toolState: ToolState = ToolState(
        selectedColorArgb = DrawingDefaults.DEFAULT_COLOR_ARGB,
        selectedWidth = DrawingDefaults.DEFAULT_WIDTH
    ),
    val syncState: SyncState = SyncState.Disconnected,
    val cacheToken: Long = 0L
) {
    val isEmpty: Boolean
        get() = committedStrokes.isEmpty() &&
            localActiveStroke == null &&
            remoteActiveStrokes.isEmpty()

    fun toCanvasState(): CanvasState = CanvasState(
        strokes = committedStrokes,
        activeStroke = localActiveStroke,
        remoteActiveStrokes = remoteActiveStrokes.values.toList(),
        isEmpty = isEmpty,
        revision = revision,
        snapshotState = snapshotState
    )
}
