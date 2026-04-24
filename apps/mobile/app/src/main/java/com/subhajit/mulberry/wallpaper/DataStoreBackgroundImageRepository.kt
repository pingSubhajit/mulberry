package com.subhajit.mulberry.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class DataStoreBackgroundImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) : BackgroundImageRepository {
    private val publicHttpClient = OkHttpClient()

    override val backgroundState: Flow<BackgroundImageState> = dataStore.data
        .map { preferences ->
            BackgroundImageState(
                assetPath = preferences[PreferenceStorage.backgroundImagePath],
                lastUpdatedAt = preferences[PreferenceStorage.backgroundImageUpdatedAt]
                    ?.toLongOrNull()
                    ?: 0L,
                selectedPresetResId = preferences[PreferenceStorage.backgroundImagePresetResId]
                    ?.toIntOrNull(),
                selectedRemoteWallpaperId =
                    preferences[PreferenceStorage.backgroundImageRemoteWallpaperId]
            )
        }
        .distinctUntilChanged()

    override suspend fun getCurrentBackgroundState(): BackgroundImageState = backgroundState.first()

    override suspend fun importBackground(uri: Uri) {
        withContext(Dispatchers.IO) {
            val backgroundFile = WallpaperFiles.backgroundFile(context)
            storeOptimizedBackground(
                destination = backgroundFile,
                loadBitmap = { decodeScaledUriBitmap(uri) }
            )
            persistSelection(
                assetPath = backgroundFile.absolutePath,
                selectedPresetResId = null,
                selectedRemoteWallpaperId = null
            )
        }
    }

    override suspend fun importBundledBackground(@DrawableRes drawableResId: Int) {
        withContext(Dispatchers.IO) {
            val backgroundFile = WallpaperFiles.backgroundFile(context)
            storeOptimizedBackground(
                destination = backgroundFile,
                loadBitmap = { decodeScaledBundledBitmap(drawableResId) }
            )
            persistSelection(
                assetPath = backgroundFile.absolutePath,
                selectedPresetResId = drawableResId,
                selectedRemoteWallpaperId = null
            )
        }
    }

    override suspend fun importRemoteBackground(remoteWallpaper: RemoteWallpaper) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(remoteWallpaper.fullImageUrl)
                .get()
                .build()
            val backgroundFile = WallpaperFiles.backgroundFile(context)
            val downloadFile = File.createTempFile("remote-wallpaper", ".img", context.cacheDir)

            try {
                publicHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Unable to download wallpaper: HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Wallpaper download returned an empty body")
                    downloadFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }

                storeOptimizedBackground(
                    destination = backgroundFile,
                    loadBitmap = { decodeScaledRemoteBitmap(downloadFile) }
                )
            } finally {
                if (downloadFile.exists()) {
                    downloadFile.delete()
                }
            }

            persistSelection(
                assetPath = backgroundFile.absolutePath,
                selectedPresetResId = null,
                selectedRemoteWallpaperId = remoteWallpaper.id
            )
        }
    }

    override suspend fun clearBackground() {
        withContext(Dispatchers.IO) {
            WallpaperFiles.backgroundFile(context).delete()
            dataStore.edit { preferences ->
                preferences.remove(PreferenceStorage.backgroundImagePath)
                preferences.remove(PreferenceStorage.backgroundImageUpdatedAt)
                preferences.remove(PreferenceStorage.backgroundImagePresetResId)
                preferences.remove(PreferenceStorage.backgroundImageRemoteWallpaperId)
            }
        }
    }

    private suspend fun persistSelection(
        assetPath: String,
        selectedPresetResId: Int?,
        selectedRemoteWallpaperId: String?
    ) {
        val now = System.currentTimeMillis()
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.backgroundImagePath] = assetPath
            preferences[PreferenceStorage.backgroundImageUpdatedAt] = now.toString()
            if (selectedPresetResId == null) {
                preferences.remove(PreferenceStorage.backgroundImagePresetResId)
            } else {
                preferences[PreferenceStorage.backgroundImagePresetResId] = selectedPresetResId.toString()
            }
            if (selectedRemoteWallpaperId == null) {
                preferences.remove(PreferenceStorage.backgroundImageRemoteWallpaperId)
            } else {
                preferences[PreferenceStorage.backgroundImageRemoteWallpaperId] =
                    selectedRemoteWallpaperId
            }
        }
    }

    private fun storeOptimizedBackground(
        destination: File,
        loadBitmap: () -> Bitmap?
    ) {
        destination.parentFile?.mkdirs()
        val bitmap = loadBitmap() ?: error("Unable to decode background image")
        try {
            destination.outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Unable to store background image"
                }
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to store background image", exception)
        } finally {
            bitmap.recycle()
        }
    }

    private fun optimizedTargetSize(): RenderSurfaceSize = resolveWallpaperRenderSurfaceSize(context)

    private fun profile(): DeviceRenderProfile = resolveDeviceRenderProfile(context)

    private fun scaleForTarget(bitmap: Bitmap, targetSize: RenderSurfaceSize): Bitmap =
        scaleBitmapToFit(
            bitmap = bitmap,
            maxWidth = targetSize.width,
            maxHeight = targetSize.height,
            maxPixels = profile().maxWallpaperPixels
        )

    private fun decodeScaledUriBitmap(uri: Uri): Bitmap? {
        val targetSize = optimizedTargetSize()
        return (decodeSampledBitmap(
            openStream = { context.contentResolver.openInputStream(uri) },
            targetWidth = targetSize.width,
            targetHeight = targetSize.height
        ) ?: decodeFullUriBitmap(uri))?.let { bitmap ->
            scaleForTarget(bitmap, targetSize)
        }
    }

    private fun decodeScaledBundledBitmap(@DrawableRes drawableResId: Int): Bitmap? {
        val targetSize = optimizedTargetSize()
        return (decodeSampledBitmap(
            openStream = { context.resources.openRawResource(drawableResId) },
            targetWidth = targetSize.width,
            targetHeight = targetSize.height
        ) ?: BitmapFactory.decodeResource(context.resources, drawableResId))?.let { bitmap ->
            scaleForTarget(bitmap, targetSize)
        }
    }

    private fun decodeScaledRemoteBitmap(downloadFile: File): Bitmap? {
        val targetSize = optimizedTargetSize()
        return (decodeSampledBitmapFromFile(
            path = downloadFile.absolutePath,
            targetWidth = targetSize.width,
            targetHeight = targetSize.height
        ) ?: decodeFullFileBitmap(downloadFile))?.let { bitmap ->
            scaleForTarget(bitmap, targetSize)
        }
    }

    private fun decodeFullUriBitmap(uri: Uri): Bitmap? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
        else -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
    }

    private fun decodeFullFileBitmap(file: File): Bitmap? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
        else -> BitmapFactory.decodeFile(file.absolutePath)
    }
}
