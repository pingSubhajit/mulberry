package com.subhajit.mulberry.drawing.data.local

import androidx.room.TypeConverter
import com.subhajit.mulberry.drawing.model.CanvasTextAlign
import com.subhajit.mulberry.drawing.model.CanvasTextFont
import com.subhajit.mulberry.drawing.model.DrawingOperationType
import com.subhajit.mulberry.drawing.model.DrawingTool
import com.subhajit.mulberry.sync.SyncOutboxStatus

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

    @TypeConverter
    fun toSyncOutboxStatus(value: String): SyncOutboxStatus = SyncOutboxStatus.valueOf(value)

    @TypeConverter
    fun fromSyncOutboxStatus(value: SyncOutboxStatus): String = value.name

    @TypeConverter
    fun toCanvasTextFont(value: String): CanvasTextFont =
        runCatching { CanvasTextFont.valueOf(value) }.getOrElse { CanvasTextFont.POPPINS }

    @TypeConverter
    fun fromCanvasTextFont(value: CanvasTextFont): String = value.name

    @TypeConverter
    fun toCanvasTextAlign(value: String): CanvasTextAlign = CanvasTextAlign.valueOf(value)

    @TypeConverter
    fun fromCanvasTextAlign(value: CanvasTextAlign): String = value.name
}
