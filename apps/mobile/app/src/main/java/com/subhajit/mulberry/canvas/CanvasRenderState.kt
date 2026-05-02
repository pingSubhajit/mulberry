package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.drawing.model.CanvasSnapshotState
import com.subhajit.mulberry.drawing.model.CanvasState
import com.subhajit.mulberry.drawing.model.CanvasElement
import com.subhajit.mulberry.drawing.model.DrawingDefaults
import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.ToolState
import com.subhajit.mulberry.sync.SyncState

data class CanvasRenderState(
    val committedStrokes: List<Stroke> = emptyList(),
    val committedElements: List<CanvasElement> = emptyList(),
    val localActiveStroke: Stroke? = null,
    val remoteActiveStrokes: Map<String, Stroke> = emptyMap(),
    val revision: Long = 0L,
    val snapshotState: CanvasSnapshotState = CanvasSnapshotState(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val toolState: ToolState = ToolState(
        strokeColorArgb = DrawingDefaults.DEFAULT_COLOR_ARGB,
        textColorArgb = DrawingDefaults.DEFAULT_COLOR_ARGB,
        selectedWidth = DrawingDefaults.DEFAULT_WIDTH
    ),
    val syncState: SyncState = SyncState.Disconnected,
    val cacheToken: Long = 0L
) {
    val isEmpty: Boolean
        get() = committedStrokes.isEmpty() &&
            committedElements.isEmpty() &&
            localActiveStroke == null &&
            remoteActiveStrokes.isEmpty()

    fun toCanvasState(): CanvasState = CanvasState(
        strokes = committedStrokes,
        elements = committedElements,
        activeStroke = localActiveStroke,
        remoteActiveStrokes = remoteActiveStrokes.values.toList(),
        isEmpty = isEmpty,
        revision = revision,
        snapshotState = snapshotState
    )
}
