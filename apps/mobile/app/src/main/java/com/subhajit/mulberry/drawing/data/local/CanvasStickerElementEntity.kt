package com.subhajit.mulberry.drawing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "canvas_sticker_element_entities")
data class CanvasStickerElementEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val zIndex: Long,
    val centerX: Float,
    val centerY: Float,
    val rotationRad: Float,
    val scale: Float,
    val packKey: String,
    val packVersion: Int,
    val stickerId: String
)
