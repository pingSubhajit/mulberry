package com.subhajit.mulberry.home

import com.subhajit.mulberry.drawing.model.DrawingTool

/**
 * Whether a tap on empty canvas starts a new element: text, a sticker, or a photo from the camera.
 *
 * Choosing Text or Sticker opens its editor at once, and empty taps wait until that first editor
 * closes. The Camera tool opens the camera in the same step, so it never waits.
 */
internal data class ToolTapPlacement(
    val allowsEmptyTap: Boolean,
    /** The tool whose first editor must close before empty taps start new elements. */
    val awaitingInitialDismissForTool: DrawingTool?,
    /** The tool whose first editor has opened. */
    val openedInitialEditorForTool: DrawingTool?
) {
    /** The state once every editor has closed, with [activeTool] chosen. */
    fun settled(activeTool: DrawingTool): ToolTapPlacement {
        val awaiting = awaitingInitialDismissForTool ?: return ToolTapPlacement(
            allowsEmptyTap = activeTool in placesOnEmptyTap,
            awaitingInitialDismissForTool = null,
            openedInitialEditorForTool = null
        )
        return when {
            // The first editor for this tool has closed, saved or not.
            activeTool == awaiting && openedInitialEditorForTool == awaiting -> RESET.copy(allowsEmptyTap = true)
            // The tool changed to one with no first editor (the camera, the brush) before it opened.
            activeTool !in opensEditorOnSelect -> RESET
            else -> this
        }
    }

    companion object {
        val RESET = ToolTapPlacement(
            allowsEmptyTap = false,
            awaitingInitialDismissForTool = null,
            openedInitialEditorForTool = null
        )

        /** Tools that start a new element when empty canvas is tapped. */
        val placesOnEmptyTap: Set<DrawingTool> = setOf(DrawingTool.TEXT, DrawingTool.STICKER, DrawingTool.PHOTO)

        /** Tools whose first editor opens a moment after they're chosen, once the tool has changed. */
        private val opensEditorOnSelect: Set<DrawingTool> = setOf(DrawingTool.TEXT, DrawingTool.STICKER)
    }
}
