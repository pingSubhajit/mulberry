package com.subhajit.elaris.drawing.model

data class ToolState(
    val activeTool: DrawingTool = DrawingTool.DRAW,
    val selectedColorArgb: Long,
    val selectedWidth: Float
)
