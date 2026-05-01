package com.subhajit.mulberry.drawing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextFont

@Entity(tableName = "canvas_text_element_entities")
data class CanvasTextElementEntity(
    @PrimaryKey val id: String,
    val text: String,
    val createdAt: Long,
    val centerX: Float,
    val centerY: Float,
    val rotationRad: Float,
    val scale: Float,
    val boxWidth: Float,
    val colorArgb: Long,
    val backgroundPillEnabled: Boolean,
    val font: CanvasTextFont,
    val alignment: CanvasTextAlign
)

