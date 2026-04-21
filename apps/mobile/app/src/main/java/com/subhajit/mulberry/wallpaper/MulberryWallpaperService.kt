package com.subhajit.mulberry.wallpaper

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MulberryWallpaperService : WallpaperService() {

    @Inject lateinit var wallpaperCoordinator: WallpaperCoordinator
    @Inject lateinit var wallpaperRenderStateLoader: WallpaperRenderStateLoader
    @Inject lateinit var backgroundImageRepository: BackgroundImageRepository

    override fun onCreateEngine(): Engine = MulberryWallpaperEngine()

    private inner class MulberryWallpaperEngine : Engine() {
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            engineScope.launch {
                WallpaperRenderBus.updates().collect {
                    drawFrame()
                }
            }
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                drawFrame()
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onDestroy() {
            engineScope.cancel()
            super.onDestroy()
        }

        private fun drawFrame() {
            engineScope.launch {
                wallpaperCoordinator.ensureSnapshotCurrent()
                val renderState = wallpaperRenderStateLoader.loadCurrentState()
                drawToSurface(renderState)
            }
        }

        private suspend fun drawToSurface(renderState: WallpaperRenderState) {
            val canvas = surfaceHolder.lockCanvas() ?: return
            try {
                canvas.drawColor(renderState.fallbackColorArgb)
                drawBitmapIfValid(
                    canvas = canvas,
                    filePath = renderState.backgroundImagePath,
                    cropToScreenViewport = false,
                    onCorruptFile = {
                        backgroundImageRepository.clearBackground()
                    }
                )
                drawBitmapIfValid(
                    canvas = canvas,
                    filePath = renderState.snapshotPath,
                    cropToScreenViewport = true,
                    onCorruptFile = {
                        File(it).delete()
                        wallpaperCoordinator.ensureSnapshotCurrent()
                        wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
                    }
                )
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        private suspend fun drawBitmapIfValid(
            canvas: Canvas,
            filePath: String?,
            cropToScreenViewport: Boolean,
            onCorruptFile: suspend (String) -> Unit
        ) {
            val path = filePath ?: return
            val bitmap = BitmapFactory.decodeFile(path)
            if (bitmap == null) {
                onCorruptFile(path)
                return
            }

            try {
                val sourceRect = if (cropToScreenViewport) {
                    centeredScreenSourceRect(
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                        screenWidth = resources.displayMetrics.widthPixels,
                        screenHeight = resources.displayMetrics.heightPixels
                    ).toAndroidRect()
                } else {
                    Rect(0, 0, bitmap.width, bitmap.height)
                }
                canvas.drawBitmap(
                    bitmap,
                    sourceRect,
                    Rect(0, 0, canvas.width, canvas.height),
                    null
                )
            } finally {
                bitmap.recycle()
            }
        }
    }
}

internal fun centeredScreenSourceRect(
    bitmapWidth: Int,
    bitmapHeight: Int,
    screenWidth: Int,
    screenHeight: Int
): ScreenSourceRect {
    val safeBitmapWidth = bitmapWidth.coerceAtLeast(1)
    val safeBitmapHeight = bitmapHeight.coerceAtLeast(1)
    val viewportWidth = screenWidth
        .takeIf { it > 0 }
        ?.coerceAtMost(safeBitmapWidth)
        ?: safeBitmapWidth
    val viewportHeight = screenHeight
        .takeIf { it > 0 }
        ?.coerceAtMost(safeBitmapHeight)
        ?: safeBitmapHeight

    val left = ((safeBitmapWidth - viewportWidth) / 2f).toInt()
    val top = ((safeBitmapHeight - viewportHeight) / 2f).toInt()

    return ScreenSourceRect(
        left,
        top,
        left + viewportWidth,
        top + viewportHeight
    )
}

internal data class ScreenSourceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

private fun ScreenSourceRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)
