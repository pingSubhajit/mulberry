package com.subhajit.mulberry.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import androidx.core.graphics.createBitmap
import com.subhajit.mulberry.R
import com.subhajit.mulberry.wallpaper.LockScreenComposer
import com.subhajit.mulberry.wallpaper.LockScreenLayers
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperRenderStateLoader
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import com.subhajit.mulberry.wallpaper.calculateInSampleSize
import com.subhajit.mulberry.wallpaper.decodeSampledBitmapFromFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The wallpaper layer of the share image, 1179×2556 and without the clock. It loads the same
 * render state as the live wallpaper and draws it through [LockScreenComposer] at this device's
 * screen size, so it's exactly the lock screen Mulberry shows here, then fills 1179×2556 from it,
 * centre-cropped.
 */
@Singleton
class ShareWallpaperSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wallpaperCoordinator: WallpaperCoordinator,
    private val wallpaperRenderStateLoader: WallpaperRenderStateLoader,
    private val wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository
) {
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /**
     * With [useRealWallpaper], the lock screen as is. Otherwise the fallback wallpaper
     * (`auth_login_bg`) dimmed with black at 20%, with the doodles drawn over it as the lock screen
     * draws them. Call off the main thread.
     */
    suspend fun renderWallpaperLayer(useRealWallpaper: Boolean): Bitmap {
        val syncEnabled = wallpaperSyncSettingsRepository.enabled.first()
        if (syncEnabled) {
            // As the live wallpaper does before it draws, so the doodles are current.
            try {
                wallpaperCoordinator.ensureSnapshotCurrent()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Unable to refresh the canvas snapshot", error)
            }
        }
        val renderState = wallpaperRenderStateLoader.loadCurrentState(wallpaperSyncEnabled = syncEnabled)
        val (screenWidth, screenHeight) = lockScreenSizePx()

        val snapshot = renderState.snapshotPath?.let { decodeSampledBitmapFromFile(it, screenWidth, screenHeight) }
        val background = if (useRealWallpaper) {
            renderState.backgroundImagePath?.let { decodeSampledBitmapFromFile(it, screenWidth, screenHeight) }
        } else {
            decodeFallbackWallpaper(screenWidth, screenHeight)
        }

        val lockScreen = createBitmap(screenWidth, screenHeight)
        LockScreenComposer.compose(
            canvas = Canvas(lockScreen),
            layers = LockScreenLayers(
                fallbackColorArgb = renderState.fallbackColorArgb,
                background = background,
                snapshot = snapshot,
                backgroundDim = if (useRealWallpaper) 0f else FALLBACK_DIM
            ),
            viewportWidthPx = screenWidth,
            viewportHeightPx = screenHeight,
            paint = filterPaint
        )
        background?.recycle()
        snapshot?.recycle()

        val layer = createBitmap(SHARE_CONTENT_WIDTH, SHARE_CONTENT_HEIGHT)
        val crop = ShareGeometry.centerCropSource(
            sourceWidth = screenWidth,
            sourceHeight = screenHeight,
            targetWidth = SHARE_CONTENT_WIDTH,
            targetHeight = SHARE_CONTENT_HEIGHT
        )
        Canvas(layer).drawBitmap(
            lockScreen,
            Rect(crop.left.roundToInt(), crop.top.roundToInt(), crop.right.roundToInt(), crop.bottom.roundToInt()),
            Rect(0, 0, SHARE_CONTENT_WIDTH, SHARE_CONTENT_HEIGHT),
            filterPaint
        )
        lockScreen.recycle()
        return layer
    }

    /** The auth screen's background (`auth_login_bg`), sampled down near the screen's size. */
    private fun decodeFallbackWallpaper(targetWidth: Int, targetHeight: Int): Bitmap? {
        val resources = context.resources
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, R.drawable.auth_login_bg, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeResource(
            resources,
            R.drawable.auth_login_bg,
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        )
    }

    /** The full screen in portrait, as the live wallpaper's surface covers it on the lock screen. */
    private fun lockScreenSizePx(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        }
        val width = metrics.widthPixels.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val height = metrics.heightPixels.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        return min(safeWidth, safeHeight) to max(safeWidth, safeHeight)
    }

    private companion object {
        const val TAG = "ShareWallpaper"
        const val FALLBACK_DIM = 0.20f
    }
}
