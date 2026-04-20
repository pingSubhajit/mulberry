package com.subhajit.elaris.drawing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stroke_entities")
data class StrokeEntity(
    @PrimaryKey val id: String,
    val colorArgb: Long,
    val width: Float,
    val createdAt: Long
)
