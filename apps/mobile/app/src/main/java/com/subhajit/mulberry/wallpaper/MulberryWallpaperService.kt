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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class MulberryWallpaperService : WallpaperService() {

    @Inject lateinit var wallpaperCoordinator: WallpaperCoordinator
    @Inject lateinit var wallpaperRenderStateLoader: WallpaperRenderStateLoader
    @Inject lateinit var backgroundImageRepository: BackgroundImageRepository

    override fun onCreateEngine(): Engine = MulberryWallpaperEngine()

    private inner class MulberryWallpaperEngine : Engine() {
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val drawMutex = Mutex()

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
                drawMutex.withLock {
                    wallpaperCoordinator.ensureSnapshotCurrent()
                    val renderState = wallpaperRenderStateLoader.loadCurrentState()
                    drawToSurface(renderState)
                }
            }
        }

        private suspend fun drawToSurface(renderState: WallpaperRenderState) {
            val canvas = surfaceHolder.lockCanvas() ?: return
            try {
                canvas.drawColor(renderState.fallbackColorArgb)
                drawBitmapIfValid(
                    canvas = canvas,
                    filePath = renderState.backgroundImagePath,
                    scaleMode = BitmapScaleMode.CENTER_CROP_TO_CANVAS,
                    onCorruptFile = {
                        backgroundImageRepository.clearBackground()
                    }
                )
                drawBitmapIfValid(
                    canvas = canvas,
                    filePath = renderState.snapshotPath,
                    scaleMode = BitmapScaleMode.CENTERED_SCREEN_VIEWPORT,
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
            scaleMode: BitmapScaleMode,
            onCorruptFile: suspend (String) -> Unit
        ) {
            val path = filePath ?: return
            val bitmap = decodeBitmapForCanvas(
                path = path,
                canvasWidth = canvas.width,
                canvasHeight = canvas.height
            )
            if (bitmap == null) {
                onCorruptFile(path)
                return
            }

            try {
                val sourceRect = when (scaleMode) {
                    BitmapScaleMode.CENTER_CROP_TO_CANVAS -> centerCropSourceRect(
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                        targetWidth = canvas.width,
                        targetHeight = canvas.height
                    ).toAndroidRect()
                    BitmapScaleMode.CENTERED_SCREEN_VIEWPORT -> centeredScreenSourceRect(
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                        screenWidth = resources.displayMetrics.widthPixels,
                        screenHeight = resources.displayMetrics.heightPixels
                    ).toAndroidRect()
                }
                val destinationRect = when (scaleMode) {
                    BitmapScaleMode.CENTER_CROP_TO_CANVAS -> Rect(0, 0, canvas.width, canvas.height)
                    BitmapScaleMode.CENTERED_SCREEN_VIEWPORT -> centeredScreenSourceRect(
                        bitmapWidth = canvas.width,
                        bitmapHeight = canvas.height,
                        screenWidth = resources.displayMetrics.widthPixels,
                        screenHeight = resources.displayMetrics.heightPixels
                    ).toAndroidRect()
                }
                canvas.drawBitmap(
                    bitmap,
                    sourceRect,
                    destinationRect,
                    null
                )
            } finally {
                bitmap.recycle()
            }
        }

        private fun decodeBitmapForCanvas(
            path: String,
            canvasWidth: Int,
            canvasHeight: Int
        ) = decodeSampledBitmapFromFile(
            path = path,
            targetWidth = canvasWidth.coerceAtLeast(resources.displayMetrics.widthPixels),
            targetHeight = canvasHeight.coerceAtLeast(resources.displayMetrics.heightPixels)
        )
    }
}

private enum class BitmapScaleMode {
    CENTER_CROP_TO_CANVAS,
    CENTERED_SCREEN_VIEWPORT
}

internal fun centerCropSourceRect(
    bitmapWidth: Int,
    bitmapHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): ScreenSourceRect {
    val safeBitmapWidth = bitmapWidth.coerceAtLeast(1)
    val safeBitmapHeight = bitmapHeight.coerceAtLeast(1)
    val safeTargetWidth = targetWidth.coerceAtLeast(1)
    val safeTargetHeight = targetHeight.coerceAtLeast(1)

    val bitmapAspectRatio = safeBitmapWidth.toFloat() / safeBitmapHeight.toFloat()
    val targetAspectRatio = safeTargetWidth.toFloat() / safeTargetHeight.toFloat()

    return if (bitmapAspectRatio > targetAspectRatio) {
        val cropWidth = (safeBitmapHeight * targetAspectRatio).toInt().coerceAtLeast(1)
        val left = ((safeBitmapWidth - cropWidth) / 2f).toInt()
        ScreenSourceRect(left, 0, left + cropWidth, safeBitmapHeight)
    } else {
        val cropHeight = (safeBitmapWidth / targetAspectRatio).toInt().coerceAtLeast(1)
        val top = ((safeBitmapHeight - cropHeight) / 2f).toInt()
        ScreenSourceRect(0, top, safeBitmapWidth, top + cropHeight)
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
