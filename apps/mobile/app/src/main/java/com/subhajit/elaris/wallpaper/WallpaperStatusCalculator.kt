package com.subhajit.elaris.wallpaper

import com.subhajit.elaris.drawing.data.local.CanvasMetadataEntity

class WallpaperStatusCalculator {
    fun calculate(
        isWallpaperSelected: Boolean,
        backgroundState: BackgroundImageState,
        metadata: CanvasMetadataEntity,
        hasSnapshotFile: Boolean,
        hasBackgroundAsset: Boolean
    ): WallpaperStatusState = WallpaperStatusState(
        isWallpaperSelected = isWallpaperSelected,
        hasSnapshot = hasSnapshotFile,
        isSnapshotCurrent = hasSnapshotFile &&
            !metadata.isSnapshotDirty &&
            metadata.lastSnapshotRevision == metadata.revision,
        hasBackgroundImage = backgroundState.isConfigured && hasBackgroundAsset,
        lastSnapshotRevision = metadata.lastSnapshotRevision,
        currentCanvasRevision = metadata.revision
    )
}
