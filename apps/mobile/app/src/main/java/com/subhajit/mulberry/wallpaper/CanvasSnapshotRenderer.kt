package com.subhajit.mulberry.wallpaper

interface CanvasSnapshotRenderer {
    suspend fun renderCurrentSnapshot(): SnapshotRenderResult

    suspend fun clearSnapshots()
}
