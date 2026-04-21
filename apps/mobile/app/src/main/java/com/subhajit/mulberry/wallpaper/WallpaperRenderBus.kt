package com.subhajit.mulberry.wallpaper

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

object WallpaperRenderBus {
    private val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun updates(): Flow<Unit> = updates

    fun requestRedraw() {
        updates.tryEmit(Unit)
    }
}
