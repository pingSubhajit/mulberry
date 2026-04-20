package com.subhajit.elaris.drawing.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stroke_point_entities",
    foreignKeys = [
        ForeignKey(
            entity = StrokeEntity::class,
            parentColumns = ["id"],
            childColumns = ["strokeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["strokeId"])]
)
data class StrokePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val strokeId: String,
    val pointIndex: Int,
    val x: Float,
    val y: Float
)
