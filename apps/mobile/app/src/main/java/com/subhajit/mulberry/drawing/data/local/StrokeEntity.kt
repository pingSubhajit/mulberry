package com.subhajit.mulberry.drawing.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stroke_entities")
data class StrokeEntity(
    @PrimaryKey val id: String,
    val colorArgb: Long,
    val width: Float,
    val createdAt: Long,
    /**
     * Where the stroke sits among the others: the last one finished is highest and drawn on top,
     * as in the server's snapshot.
     */
    @ColumnInfo(defaultValue = "0") val finishOrder: Long = 0L
)
