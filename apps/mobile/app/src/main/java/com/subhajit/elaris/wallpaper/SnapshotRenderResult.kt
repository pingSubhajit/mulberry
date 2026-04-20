package com.subhajit.elaris.wallpaper

data class SnapshotRenderResult(
    val revision: Long,
    val imagePath: String,
    val renderedAt: Long,
    val width: Int,
    val height: Int
)
