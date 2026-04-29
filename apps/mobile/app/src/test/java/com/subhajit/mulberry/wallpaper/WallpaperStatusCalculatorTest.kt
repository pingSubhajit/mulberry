package com.subhajit.mulberry.wallpaper

import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperStatusCalculatorTest {
    private val calculator = WallpaperStatusCalculator()

    @Test
    fun currentSnapshotIsRecognizedWhenFileAndRevisionMatch() {
        val status = calculator.calculate(
            isWallpaperSelectedOnHome = true,
            isWallpaperSelectedOnLock = false,
            backgroundState = BackgroundImageState(assetPath = "/tmp/background.png"),
            metadata = CanvasMetadataEntity.default().copy(
                revision = 4L,
                isSnapshotDirty = false,
                lastSnapshotRevision = 4L,
                cachedImagePath = "/tmp/snapshot.png"
            ),
            hasSnapshotFile = true,
            hasBackgroundAsset = true
        )

        assertTrue(status.isWallpaperSelected)
        assertTrue(status.hasSnapshot)
        assertTrue(status.isSnapshotCurrent)
        assertTrue(status.hasBackgroundImage)
        assertFalse(status.requiresRecovery)
    }

    @Test
    fun missingSnapshotRequiresRecovery() {
        val status = calculator.calculate(
            isWallpaperSelectedOnHome = false,
            isWallpaperSelectedOnLock = false,
            backgroundState = BackgroundImageState(),
            metadata = CanvasMetadataEntity.default().copy(
                revision = 3L,
                isSnapshotDirty = false,
                lastSnapshotRevision = 3L,
                cachedImagePath = "/tmp/missing.png"
            ),
            hasSnapshotFile = false,
            hasBackgroundAsset = false
        )

        assertFalse(status.hasSnapshot)
        assertFalse(status.isSnapshotCurrent)
        assertTrue(status.requiresRecovery)
    }
}
