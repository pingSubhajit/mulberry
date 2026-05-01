package com.subhajit.mulberry.wallpaper

interface CanvasSnapshotRenderer {
    suspend fun renderCurrentSnapshot(canvasKey: String): SnapshotRenderResult

    suspend fun clearSnapshots()
}
