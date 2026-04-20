package com.subhajit.elaris.wallpaper

interface CanvasSnapshotRenderer {
    suspend fun renderCurrentSnapshot(): SnapshotRenderResult

    suspend fun clearSnapshots()
}
