package com.subhajit.mulberry.drawing.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stroke_entities",
    indices = [
        Index(value = ["canvasKey", "strokeId"], unique = true),
        Index(value = ["canvasKey", "createdAt"])
    ]
)
data class StrokeEntity(
    @PrimaryKey val key: String,
    val canvasKey: String,
    val strokeId: String,
    val colorArgb: Long,
    val width: Float,
    val createdAt: Long
)
