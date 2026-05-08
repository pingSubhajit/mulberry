package com.subhajit.mulberry.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface CanvasDebugOptionsRepository {
    val showElementBounds: Flow<Boolean>
    suspend fun setShowElementBounds(enabled: Boolean)
}

@Singleton
class DataStoreCanvasDebugOptionsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : CanvasDebugOptionsRepository {
    override val showElementBounds: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceStorage.canvasShowElementBounds] ?: false
    }

    override suspend fun setShowElementBounds(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.canvasShowElementBounds] = enabled
        }
    }
}

