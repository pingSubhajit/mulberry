package com.subhajit.mulberry.wallpaper

import android.app.ActivityManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal data class DeviceRenderProfile(
    val isLowRamDevice: Boolean,
    val memoryClassMb: Int,
    val maxCanvasCachePixels: Int,
    val maxWallpaperPixels: Int
)

internal data class RenderSurfaceSize(
    val width: Int,
    val height: Int
)

internal fun resolveDeviceRenderProfile(context: Context): DeviceRenderProfile {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryClassMb = activityManager?.memoryClass ?: DEFAULT_MEMORY_CLASS_MB
    val isLowRamDevice = activityManager?.isLowRamDevice == true || memoryClassMb <= LOW_RAM_MEMORY_CLASS_MB

    return DeviceRenderProfile(
        isLowRamDevice = isLowRamDevice,
        memoryClassMb = memoryClassMb,
        maxCanvasCachePixels = if (isLowRamDevice) {
            LOW_RAM_CANVAS_CACHE_PIXELS
        } else {
            DEFAULT_CANVAS_CACHE_PIXELS
        },
        maxWallpaperPixels = if (isLowRamDevice) {
            LOW_RAM_WALLPAPER_PIXELS
        } else {
            DEFAULT_WALLPAPER_PIXELS
        }
    )
}

internal fun resolveWallpaperRenderSurfaceSize(
    context: Context,
    profile: DeviceRenderProfile = resolveDeviceRenderProfile(context)
): RenderSurfaceSize {
    val displayMetrics = context.resources.displayMetrics
    val wallpaperManager = WallpaperManager.getInstance(context)
    return resolveWallpaperRenderSurfaceSize(
        displayWidth = displayMetrics.widthPixels,
        displayHeight = displayMetrics.heightPixels,
        desiredWidth = wallpaperManager.desiredMinimumWidth,
        desiredHeight = wallpaperManager.desiredMinimumHeight,
        profile = profile
    )
}

internal fun resolveWallpaperRenderSurfaceSize(
    displayWidth: Int,
    displayHeight: Int,
    desiredWidth: Int,
    desiredHeight: Int,
    profile: DeviceRenderProfile
): RenderSurfaceSize {
    val safeDisplayWidth = displayWidth.coerceAtLeast(1)
    val safeDisplayHeight = displayHeight.coerceAtLeast(1)
    val widthFactor = if (profile.isLowRamDevice) LOW_RAM_WALLPAPER_WIDTH_FACTOR else DEFAULT_WALLPAPER_WIDTH_FACTOR
    val heightFactor =
        if (profile.isLowRamDevice) LOW_RAM_WALLPAPER_HEIGHT_FACTOR else DEFAULT_WALLPAPER_HEIGHT_FACTOR

    val requestedWidth = desiredWidth.takeIf { it > 0 } ?: safeDisplayWidth
    val requestedHeight = desiredHeight.takeIf { it > 0 } ?: safeDisplayHeight

    val boundedWidth = requestedWidth
        .coerceAtLeast(safeDisplayWidth)
        .coerceAtMost((safeDisplayWidth * widthFactor).roundToInt().coerceAtLeast(safeDisplayWidth))
    val boundedHeight = requestedHeight
        .coerceAtLeast(safeDisplayHeight)
        .coerceAtMost((safeDisplayHeight * heightFactor).roundToInt().coerceAtLeast(safeDisplayHeight))

    return scaleSizeToFitPixelBudget(
        width = boundedWidth,
        height = boundedHeight,
        minWidth = safeDisplayWidth,
        minHeight = safeDisplayHeight,
        maxPixels = max(profile.maxWallpaperPixels, safeDisplayWidth * safeDisplayHeight)
    )
}

internal fun canUseCommittedCanvasCache(
    width: Int,
    height: Int,
    profile: DeviceRenderProfile
): Boolean {
    if (width <= 0 || height <= 0) return false
    val pixelCount = width.toLong() * height.toLong()
    return pixelCount in 1..profile.maxCanvasCachePixels.toLong()
}

internal fun calculateInSampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    val safeTargetWidth = targetWidth.coerceAtLeast(1)
    val safeTargetHeight = targetHeight.coerceAtLeast(1)
    val widthRatio = sourceWidth / safeTargetWidth
    val heightRatio = sourceHeight / safeTargetHeight
    return max(1, minOf(widthRatio, heightRatio))
}

internal fun decodeSampledBitmap(
    openStream: () -> InputStream?,
    targetWidth: Int,
    targetHeight: Int
): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    openStream()?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    } ?: return null

    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    return openStream()?.use { input ->
        BitmapFactory.decodeStream(
            input,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(
                    sourceWidth = bounds.outWidth,
                    sourceHeight = bounds.outHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }
}

internal fun decodeSampledBitmapFromFile(
    path: String,
    targetWidth: Int,
    targetHeight: Int
): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    )
}

internal fun scaleBitmapToFit(
    bitmap: Bitmap,
    maxWidth: Int,
    maxHeight: Int,
    maxPixels: Int
): Bitmap {
    val boundedSize = scaleSizeToFitPixelBudget(
        width = bitmap.width,
        height = bitmap.height,
        minWidth = 1,
        minHeight = 1,
        maxPixels = maxPixels,
        maxWidth = maxWidth.coerceAtLeast(1),
        maxHeight = maxHeight.coerceAtLeast(1)
    )
    if (boundedSize.width == bitmap.width && boundedSize.height == bitmap.height) {
        return bitmap
    }

    return Bitmap.createScaledBitmap(bitmap, boundedSize.width, boundedSize.height, true)
}

private fun scaleSizeToFitPixelBudget(
    width: Int,
    height: Int,
    minWidth: Int,
    minHeight: Int,
    maxPixels: Int,
    maxWidth: Int = width,
    maxHeight: Int = height
): RenderSurfaceSize {
    var boundedWidth = width.coerceIn(minWidth, maxWidth.coerceAtLeast(minWidth))
    var boundedHeight = height.coerceIn(minHeight, maxHeight.coerceAtLeast(minHeight))
    val currentPixels = boundedWidth.toLong() * boundedHeight.toLong()
    if (currentPixels <= maxPixels.toLong()) {
        return RenderSurfaceSize(boundedWidth, boundedHeight)
    }

    val scale = kotlin.math.sqrt(maxPixels.toDouble() / currentPixels.toDouble())
    boundedWidth = max(minWidth, (boundedWidth * scale).roundToInt())
    boundedHeight = max(minHeight, (boundedHeight * scale).roundToInt())
    return RenderSurfaceSize(boundedWidth, boundedHeight)
}

private const val DEFAULT_MEMORY_CLASS_MB = 256
private const val LOW_RAM_MEMORY_CLASS_MB = 192
private const val LOW_RAM_CANVAS_CACHE_PIXELS = 3_000_000
private const val DEFAULT_CANVAS_CACHE_PIXELS = 8_000_000
private const val LOW_RAM_WALLPAPER_PIXELS = 3_200_000
private const val DEFAULT_WALLPAPER_PIXELS = 6_500_000
private const val LOW_RAM_WALLPAPER_WIDTH_FACTOR = 1.1f
private const val DEFAULT_WALLPAPER_WIDTH_FACTOR = 1.25f
private const val LOW_RAM_WALLPAPER_HEIGHT_FACTOR = 1.0f
private const val DEFAULT_WALLPAPER_HEIGHT_FACTOR = 1.05f
