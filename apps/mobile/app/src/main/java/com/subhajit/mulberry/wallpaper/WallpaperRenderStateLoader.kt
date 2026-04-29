package com.subhajit.mulberry.wallpaper

import com.subhajit.mulberry.drawing.data.local.CanvasMetadataDao
import com.subhajit.mulberry.drawing.data.local.CanvasMetadataEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WallpaperRenderStateLoader @Inject constructor(
    private val canvasMetadataDao: CanvasMetadataDao,
    private val backgroundImageRepository: BackgroundImageRepository
) {
    suspend fun loadCurrentState(wallpaperSyncEnabled: Boolean): WallpaperRenderState {
        val metadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
        val backgroundState = backgroundImageRepository.getCurrentBackgroundState()

        return WallpaperRenderState(
            snapshotPath = metadata.cachedImagePath
                ?.takeIf { wallpaperSyncEnabled && File(it).exists() },
            backgroundImagePath = backgroundState.assetPath
                ?.takeIf { File(it).exists() }
        )
    }
}
