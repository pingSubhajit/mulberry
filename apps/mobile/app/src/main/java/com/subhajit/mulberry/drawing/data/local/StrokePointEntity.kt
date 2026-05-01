package com.subhajit.mulberry.drawing.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stroke_point_entities",
    foreignKeys = [
        ForeignKey(
            entity = StrokeEntity::class,
            parentColumns = ["key"],
            childColumns = ["strokeKey"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["strokeKey"])]
)
data class StrokePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val strokeKey: String,
    val pointIndex: Int,
    val x: Float,
    val y: Float
)
