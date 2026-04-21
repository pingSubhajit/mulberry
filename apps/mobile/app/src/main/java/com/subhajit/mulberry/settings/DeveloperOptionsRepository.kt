package com.subhajit.mulberry.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface DeveloperOptionsRepository {
    val enabled: Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}

@Singleton
class DataStoreDeveloperOptionsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : DeveloperOptionsRepository {
    override val enabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferenceStorage.developerOptionsEnabled] ?: false
    }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.developerOptionsEnabled] = enabled
        }
    }
}
