package com.subhajit.mulberry.drawing.data.local

import androidx.room.TypeConverter
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.DrawingTool

class RoomConverters {
    @TypeConverter
    fun toDrawingTool(value: String): DrawingTool = DrawingTool.valueOf(value)

    @TypeConverter
    fun fromDrawingTool(value: DrawingTool): String = value.name

    @TypeConverter
    fun toDrawingOperationType(value: String): DrawingOperationType =
        DrawingOperationType.valueOf(value)

    @TypeConverter
    fun fromDrawingOperationType(value: DrawingOperationType): String = value.name
}
