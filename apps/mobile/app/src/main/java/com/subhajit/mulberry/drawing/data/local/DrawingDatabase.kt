package com.subhajit.mulberry.drawing.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.subhajit.mulberry.sync.SyncOutboxDao
import com.subhajit.mulberry.sync.SyncOutboxEntity

@Database(
    entities = [
        StrokeEntity::class,
        StrokePointEntity::class,
        DrawingOperationEntity::class,
        CanvasTextElementEntity::class,
        CanvasStickerElementEntity::class,
        CanvasMetadataEntity::class,
        SyncOutboxEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class DrawingDatabase : RoomDatabase() {
    abstract fun drawingDao(): DrawingDao
    abstract fun drawingOperationsDao(): DrawingOperationsDao
    abstract fun canvasTextElementDao(): CanvasTextElementDao
    abstract fun canvasStickerElementDao(): CanvasStickerElementDao
    abstract fun canvasMetadataDao(): CanvasMetadataDao
    abstract fun syncOutboxDao(): SyncOutboxDao
}
