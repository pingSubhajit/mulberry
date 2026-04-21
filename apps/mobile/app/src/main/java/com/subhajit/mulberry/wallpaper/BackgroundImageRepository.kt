package com.subhajit.mulberry.wallpaper

import android.net.Uri
import androidx.annotation.DrawableRes
import kotlinx.coroutines.flow.Flow

interface BackgroundImageRepository {
    val backgroundState: Flow<BackgroundImageState>

    suspend fun getCurrentBackgroundState(): BackgroundImageState

    suspend fun importBackground(uri: Uri)

    suspend fun importBundledBackground(@DrawableRes drawableResId: Int)

    suspend fun clearBackground()
}
