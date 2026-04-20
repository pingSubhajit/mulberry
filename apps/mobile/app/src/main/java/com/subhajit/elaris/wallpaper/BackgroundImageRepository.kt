package com.subhajit.elaris.wallpaper

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface BackgroundImageRepository {
    val backgroundState: Flow<BackgroundImageState>

    suspend fun getCurrentBackgroundState(): BackgroundImageState

    suspend fun importBackground(uri: Uri)

    suspend fun clearBackground()
}
