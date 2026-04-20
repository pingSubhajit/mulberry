package com.subhajit.elaris.drawing.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        StrokeEntity::class,
        StrokePointEntity::class,
        DrawingOperationEntity::class,
        CanvasMetadataEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class DrawingDatabase : RoomDatabase() {
    abstract fun drawingDao(): DrawingDao
    abstract fun drawingOperationsDao(): DrawingOperationsDao
    abstract fun canvasMetadataDao(): CanvasMetadataDao
}
