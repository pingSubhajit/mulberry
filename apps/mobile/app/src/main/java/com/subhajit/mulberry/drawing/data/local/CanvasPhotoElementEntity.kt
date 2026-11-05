package com.subhajit.mulberry.drawing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A photo on the canvas. It shares the stickers' zIndex scale: the two are one stack. */
@Entity(tableName = "canvas_photo_element_entities")
data class CanvasPhotoElementEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val zIndex: Long,
    val centerX: Float,
    val centerY: Float,
    val rotationRad: Float,
    val scale: Float,
    val photoId: String,
    val cutoutPhotoId: String?,
    val aspectRatio: Float,
    /** A [com.subhajit.mulberry.drawing.model.CanvasPhotoStyle] name; anything else reads as a frame. */
    val style: String
)
