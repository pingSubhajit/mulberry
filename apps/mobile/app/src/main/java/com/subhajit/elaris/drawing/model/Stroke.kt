package com.subhajit.elaris.drawing.model

data class Stroke(
    val id: String,
    val colorArgb: Long,
    val width: Float,
    val points: List<StrokePoint>,
    val createdAt: Long
)
