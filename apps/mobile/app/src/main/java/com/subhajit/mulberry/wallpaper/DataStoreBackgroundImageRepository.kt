package com.subhajit.mulberry.wallpaper

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
            copyUriToFile(
                contentResolver = context.contentResolver,
                uri = uri,
                destination = backgroundFile
            )

            val now = System.currentTimeMillis()
            dataStore.edit { preferences ->
                preferences[PreferenceStorage.backgroundImagePath] = backgroundFile.absolutePath
                preferences[PreferenceStorage.backgroundImageUpdatedAt] = now.toString()
                preferences.remove(PreferenceStorage.backgroundImagePresetResId)
                preferences.remove(PreferenceStorage.backgroundImageRemoteWallpaperId)
            }
        }
    }

    override suspend fun importBundledBackground(@DrawableRes drawableResId: Int) {
        withContext(Dispatchers.IO) {
            val backgroundFile = WallpaperFiles.backgroundFile(context)
            copyDrawableToFile(
                drawableResId = drawableResId,
                destination = backgroundFile
            )

            val now = System.currentTimeMillis()
            dataStore.edit { preferences ->
                preferences[PreferenceStorage.backgroundImagePath] = backgroundFile.absolutePath
                preferences[PreferenceStorage.backgroundImageUpdatedAt] = now.toString()
                preferences[PreferenceStorage.backgroundImagePresetResId] = drawableResId.toString()
                preferences.remove(PreferenceStorage.backgroundImageRemoteWallpaperId)
            }
        }
    }

    override suspend fun importRemoteBackground(remoteWallpaper: RemoteWallpaper) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(remoteWallpaper.fullImageUrl)
                .get()
                .build()
            val backgroundFile = WallpaperFiles.backgroundFile(context)
            backgroundFile.parentFile?.mkdirs()

            publicHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Unable to download wallpaper: HTTP ${response.code}")
                }
                val body = response.body ?: error("Wallpaper download returned an empty body")
                val bitmap = body.byteStream().use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: error("Unable to decode downloaded wallpaper")

                try {
                    backgroundFile.outputStream().use { output ->
                        check(
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        ) {
                            "Unable to store downloaded wallpaper"
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }

            val now = System.currentTimeMillis()
            dataStore.edit { preferences ->
                preferences[PreferenceStorage.backgroundImagePath] = backgroundFile.absolutePath
                preferences[PreferenceStorage.backgroundImageUpdatedAt] = now.toString()
                preferences.remove(PreferenceStorage.backgroundImagePresetResId)
                preferences[PreferenceStorage.backgroundImageRemoteWallpaperId] = remoteWallpaper.id
            }
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

    private fun copyUriToFile(
        contentResolver: ContentResolver,
        uri: Uri,
        destination: File
    ) {
        destination.parentFile?.mkdirs()
        contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to open background image")
    }

    private fun copyDrawableToFile(
        @DrawableRes drawableResId: Int,
        destination: File
    ) {
        destination.parentFile?.mkdirs()
        context.resources.openRawResource(drawableResId).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
