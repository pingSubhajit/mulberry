package com.subhajit.mulberry.canvas

import com.subhajit.mulberry.drawing.model.Stroke
import com.subhajit.mulberry.drawing.model.StrokePoint
import com.subhajit.mulberry.drawing.model.CanvasTextElement
import com.subhajit.mulberry.sync.ServerCanvasOperation

sealed interface CanvasRuntimeEvent {
    data class CanvasViewportChanged(val widthPx: Int, val heightPx: Int) : CanvasRuntimeEvent
    data class LocalPress(val point: StrokePoint) : CanvasRuntimeEvent
    data class LocalDrag(val point: StrokePoint) : CanvasRuntimeEvent
    data object LocalRelease : CanvasRuntimeEvent
    data class EraseAt(val point: StrokePoint) : CanvasRuntimeEvent
    data object ClearCanvas : CanvasRuntimeEvent
    data object Undo : CanvasRuntimeEvent
    data object Redo : CanvasRuntimeEvent
    data class RemoteOperation(val operation: ServerCanvasOperation) : CanvasRuntimeEvent
    data class RemoteBatch(val operations: List<ServerCanvasOperation>) : CanvasRuntimeEvent
    data class RecoveryOperations(
        val operations: List<ServerCanvasOperation>,
        val publishAtomically: Boolean = true
    ) : CanvasRuntimeEvent

    data class RecoverySnapshot(
        val strokes: List<Stroke>,
        val textElements: List<CanvasTextElement>,
        val serverRevision: Long
    ) : CanvasRuntimeEvent

    data class AddTextElement(val element: CanvasTextElement) : CanvasRuntimeEvent
    data class UpdateTextElement(val element: CanvasTextElement) : CanvasRuntimeEvent
    data class DeleteTextElement(val elementId: String) : CanvasRuntimeEvent

    data class FlowControl(
        val mode: FlowControlMode,
        val maxAppendHz: Int,
        val reason: String?
    ) : CanvasRuntimeEvent
}

enum class FlowControlMode {
    NORMAL,
    SLOW_DOWN
}
