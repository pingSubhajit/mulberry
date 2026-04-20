package com.subhajit.elaris.wallpaper

import com.subhajit.elaris.drawing.data.local.CanvasMetadataDao
import com.subhajit.elaris.drawing.data.local.CanvasMetadataEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WallpaperRenderStateLoader @Inject constructor(
    private val canvasMetadataDao: CanvasMetadataDao,
    private val backgroundImageRepository: BackgroundImageRepository
) {
    suspend fun loadCurrentState(): WallpaperRenderState {
        val metadata = canvasMetadataDao.getMetadata() ?: CanvasMetadataEntity.default()
        val backgroundState = backgroundImageRepository.getCurrentBackgroundState()

        return WallpaperRenderState(
            snapshotPath = metadata.cachedImagePath
                ?.takeIf { File(it).exists() },
            backgroundImagePath = backgroundState.assetPath
                ?.takeIf { File(it).exists() }
        )
    }
}
