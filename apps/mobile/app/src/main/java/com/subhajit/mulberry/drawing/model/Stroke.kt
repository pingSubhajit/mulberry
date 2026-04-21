package com.subhajit.mulberry.drawing.model

data class Stroke(
    val id: String,
    val colorArgb: Long,
    val width: Float,
    val points: List<StrokePoint>,
    val createdAt: Long
)
