package com.subhajit.mulberry.wallpaper

import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity

class WallpaperStatusCalculator {
    fun calculate(
        isWallpaperSelectedOnHome: Boolean,
        isWallpaperSelectedOnLock: Boolean,
        backgroundState: BackgroundImageState,
        metadata: CanvasMetadataEntity,
        hasSnapshotFile: Boolean,
        hasBackgroundAsset: Boolean
    ): WallpaperStatusState = WallpaperStatusState(
        isWallpaperSelectedOnHome = isWallpaperSelectedOnHome,
        isWallpaperSelectedOnLock = isWallpaperSelectedOnLock,
        hasSnapshot = hasSnapshotFile,
        isSnapshotCurrent = hasSnapshotFile &&
            !metadata.isSnapshotDirty &&
            metadata.lastSnapshotRevision == metadata.revision,
        hasBackgroundImage = backgroundState.isConfigured && hasBackgroundAsset,
        lastSnapshotRevision = metadata.lastSnapshotRevision,
        currentCanvasRevision = metadata.revision
    )
}
