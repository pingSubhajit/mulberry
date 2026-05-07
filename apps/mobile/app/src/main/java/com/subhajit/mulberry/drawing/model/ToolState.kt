package com.subhajit.mulberry.drawing.model

data class ToolState(
    val activeTool: DrawingTool = DrawingTool.NONE,
    val strokeColorArgb: Long,
    val textColorArgb: Long,
    val selectedWidth: Float
) {
    val selectedColorArgb: Long
        get() = when (activeTool) {
            DrawingTool.TEXT -> textColorArgb
            else -> strokeColorArgb
        }
}
