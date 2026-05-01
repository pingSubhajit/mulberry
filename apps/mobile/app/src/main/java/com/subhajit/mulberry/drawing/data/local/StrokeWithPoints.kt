package com.subhajit.mulberry.drawing.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class StrokeWithPoints(
    @Embedded val stroke: StrokeEntity,
    @Relation(
        parentColumn = "key",
        entityColumn = "strokeKey",
        entity = StrokePointEntity::class
    )
    val points: List<StrokePointEntity>
)
