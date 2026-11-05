package com.subhajit.mulberry.home

import com.subhajit.mulberry.drawing.model.DrawingTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTapPlacementTest {
    @Test
    fun theCameraToolStartsAPhotoOnEmptyCanvasOnceTheCameraCloses() {
        val settled = ToolTapPlacement.RESET.settled(DrawingTool.PHOTO)
        assertTrue(settled.allowsEmptyTap)
    }

    @Test
    fun textAndStickerToolsStartElementsOnEmptyCanvas() {
        assertTrue(ToolTapPlacement.RESET.settled(DrawingTool.TEXT).allowsEmptyTap)
        assertTrue(ToolTapPlacement.RESET.settled(DrawingTool.STICKER).allowsEmptyTap)
    }

    @Test
    fun theBrushAndEraserNeverStartElements() {
        assertFalse(ToolTapPlacement.RESET.settled(DrawingTool.DRAW).allowsEmptyTap)
        assertFalse(ToolTapPlacement.RESET.settled(DrawingTool.ERASE).allowsEmptyTap)
        assertFalse(ToolTapPlacement.RESET.settled(DrawingTool.NONE).allowsEmptyTap)
    }

    @Test
    fun closingTheCameraWithNoPhotosDropsTheToolAndEmptyTaps() {
        // The camera closed with the tool still on, then the tool fell back to the brush.
        val afterClose = ToolTapPlacement.RESET.settled(DrawingTool.PHOTO)
        assertFalse(afterClose.settled(DrawingTool.DRAW).allowsEmptyTap)
    }

    @Test
    fun textWaitsForItsFirstEditorToClose() {
        val chosen = ToolTapPlacement(
            allowsEmptyTap = false,
            awaitingInitialDismissForTool = DrawingTool.TEXT,
            openedInitialEditorForTool = null
        )
        assertEquals(chosen, chosen.settled(DrawingTool.TEXT))

        val firstEditorClosed = chosen.copy(openedInitialEditorForTool = DrawingTool.TEXT)
        assertEquals(ToolTapPlacement.RESET.copy(allowsEmptyTap = true), firstEditorClosed.settled(DrawingTool.TEXT))
    }

    @Test
    fun choosingTheCameraWhileTextWaitsStopsTheWait() {
        val waiting = ToolTapPlacement(
            allowsEmptyTap = false,
            awaitingInitialDismissForTool = DrawingTool.TEXT,
            openedInitialEditorForTool = null
        )
        val settled = waiting.settled(DrawingTool.PHOTO)
        assertEquals(ToolTapPlacement.RESET, settled)
        // The next pass, with nothing waiting, lets the camera start photos.
        assertTrue(settled.settled(DrawingTool.PHOTO).allowsEmptyTap)
    }
}
