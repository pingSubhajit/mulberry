package com.subhajit.mulberry.wallpaper

import kotlinx.coroutines.flow.Flow

interface WallpaperCoordinator {
    suspend fun ensureSnapshotCurrent()

    suspend fun notifyWallpaperUpdatedIfSelected()

    suspend fun notifyWallpaperUpdated()

    fun wallpaperStatus(): Flow<WallpaperStatusState>
}
