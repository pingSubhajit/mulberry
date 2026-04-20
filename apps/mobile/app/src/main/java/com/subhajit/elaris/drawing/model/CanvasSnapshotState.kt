package com.subhajit.elaris.drawing.model

data class CanvasSnapshotState(
    val isDirty: Boolean = true,
    val lastSnapshotRevision: Long = 0L,
    val cachedImagePath: String? = null
)
