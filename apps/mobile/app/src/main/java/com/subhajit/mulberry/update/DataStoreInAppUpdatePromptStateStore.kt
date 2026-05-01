package com.subhajit.mulberry.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class DataStoreInAppUpdatePromptStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : InAppUpdatePromptStateStore {

    override suspend fun get(): InAppUpdatePromptState = dataStore.data.first().toState()

    override suspend fun updateAndGet(
        transform: (InAppUpdatePromptState) -> InAppUpdatePromptState
    ): InAppUpdatePromptState {
        var updated = InAppUpdatePromptState()
        dataStore.edit { preferences ->
            val current = preferences.toState()
            updated = transform(current)
            updateNullable(
                preferences,
                PreferenceStorage.inAppUpdateDeclinedVersionCode,
                updated.declinedVersionCode
            )
            updateNullable(
                preferences,
                PreferenceStorage.inAppUpdateDeclinedAtMs,
                updated.declinedAtMs
            )
        }
        return updated
    }

    private fun Preferences.toState(): InAppUpdatePromptState = InAppUpdatePromptState(
        declinedVersionCode = this[PreferenceStorage.inAppUpdateDeclinedVersionCode],
        declinedAtMs = this[PreferenceStorage.inAppUpdateDeclinedAtMs]
    )

    private fun <T> updateNullable(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<T>,
        value: T?
    ) {
        if (value == null) {
            preferences.remove(key)
        } else {
            preferences[key] = value
        }
    }
}

