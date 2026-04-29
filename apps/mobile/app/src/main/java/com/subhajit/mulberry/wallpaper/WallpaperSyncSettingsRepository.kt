package com.subhajit.mulberry.wallpaper

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface WallpaperSyncSettingsRepository {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}

@Singleton
class DataStoreWallpaperSyncSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : WallpaperSyncSettingsRepository {
    override val enabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceStorage.wallpaperSyncEnabled] ?: true
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.wallpaperSyncEnabled] = enabled
        }
    }
}

