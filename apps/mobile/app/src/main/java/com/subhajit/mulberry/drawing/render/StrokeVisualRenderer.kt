package com.subhajit.mulberry.drawing.render

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.subhajit.mulberry.drawing.model.Stroke

interface StrokeVisualRenderer {
    fun DrawScope.drawStroke(stroke: Stroke)
}
