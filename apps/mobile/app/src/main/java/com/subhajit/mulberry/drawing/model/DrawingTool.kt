package com.subhajit.mulberry.drawing.model

enum class DrawingTool {
    /**
     * No tool. Older builds could leave the canvas without one; it is still read from storage,
     * but the canvas always has a tool now, as on iPhone, so it means the brush.
     */
    NONE,
    DRAW,
    ERASE,
    TEXT,
    STICKER,
    /** The camera: places photos, and moves and edits the ones already there. */
    PHOTO
}

/** The tool in effect: an old "no tool" is the brush. */
fun DrawingTool.orBrush(): DrawingTool = if (this == DrawingTool.NONE) DrawingTool.DRAW else this
