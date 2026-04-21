package com.subhajit.mulberry.drawing.model

data class ToolState(
    val activeTool: DrawingTool = DrawingTool.DRAW,
    val selectedColorArgb: Long,
    val selectedWidth: Float
)
