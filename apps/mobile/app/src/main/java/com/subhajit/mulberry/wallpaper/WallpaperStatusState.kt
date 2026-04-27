package com.subhajit.mulberry.wallpaper

data class WallpaperStatusState(
    val isWallpaperSelectedOnHome: Boolean = false,
    val isWallpaperSelectedOnLock: Boolean = false,
    val hasSnapshot: Boolean = false,
    val isSnapshotCurrent: Boolean = false,
    val hasBackgroundImage: Boolean = false,
    val lastSnapshotRevision: Long = 0L,
    val currentCanvasRevision: Long = 0L
) {
    val isWallpaperSelected: Boolean
        get() = isWallpaperSelectedOnHome || isWallpaperSelectedOnLock

    val requiresRecovery: Boolean
        get() = !hasSnapshot || !isSnapshotCurrent
}
