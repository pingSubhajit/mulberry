package com.subhajit.elaris.drawing.data.local

import androidx.room.Embedded
import androidx.room.Relation

data class StrokeWithPoints(
    @Embedded val stroke: StrokeEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "strokeId",
        entity = StrokePointEntity::class
    )
    val points: List<StrokePointEntity>
)
