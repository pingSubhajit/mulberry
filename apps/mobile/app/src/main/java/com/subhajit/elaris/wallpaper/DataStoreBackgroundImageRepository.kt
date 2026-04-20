package com.subhajit.elaris.wallpaper

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.elaris.core.data.PreferenceStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class DataStoreBackgroundImageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) : BackgroundImageRepository {

    override val backgroundState: Flow<BackgroundImageState> = dataStore.data
        .map { preferences ->
            BackgroundImageState(
                assetPath = preferences[PreferenceStorage.backgroundImagePath],
                lastUpdatedAt = preferences[PreferenceStorage.backgroundImageUpdatedAt]
                    ?.toLongOrNull()
                    ?: 0L
            )
        }
        .distinctUntilChanged()

    override suspend fun getCurrentBackgroundState(): BackgroundImageState = backgroundState.first()

    override suspend fun importBackground(uri: Uri) {
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
        }
    }

    override suspend fun clearBackground() {
        WallpaperFiles.backgroundFile(context).delete()
        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.backgroundImagePath)
            preferences.remove(PreferenceStorage.backgroundImageUpdatedAt)
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
}
