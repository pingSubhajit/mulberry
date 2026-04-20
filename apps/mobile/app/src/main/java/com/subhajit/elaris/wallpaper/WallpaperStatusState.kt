package com.subhajit.elaris.wallpaper

data class WallpaperStatusState(
    val isWallpaperSelected: Boolean = false,
    val hasSnapshot: Boolean = false,
    val isSnapshotCurrent: Boolean = false,
    val hasBackgroundImage: Boolean = false,
    val lastSnapshotRevision: Long = 0L,
    val currentCanvasRevision: Long = 0L
) {
    val requiresRecovery: Boolean
        get() = !hasSnapshot || !isSnapshotCurrent
}
