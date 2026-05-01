package com.subhajit.mulberry.wallpaper

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WallpaperRenderStateLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backgroundImageRepository: BackgroundImageRepository
) {
    suspend fun loadCurrentState(wallpaperSyncEnabled: Boolean): WallpaperRenderState {
        val backgroundState = backgroundImageRepository.getCurrentBackgroundState()
        val snapshotFile = WallpaperFiles.snapshotFile(context)

        return WallpaperRenderState(
            snapshotPath = snapshotFile.absolutePath.takeIf { wallpaperSyncEnabled && snapshotFile.exists() },
            backgroundImagePath = backgroundState.assetPath
                ?.takeIf { File(it).exists() }
        )
    }
}
