package com.subhajit.mulberry.drawing.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.subhajit.mulberry.drawing.model.DrawingOperationType

@Entity(tableName = "drawing_operation_entities")
data class DrawingOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: DrawingOperationType,
    val strokeId: String? = null,
    val payload: String? = null,
    val revision: Long,
    val createdAt: Long
)
