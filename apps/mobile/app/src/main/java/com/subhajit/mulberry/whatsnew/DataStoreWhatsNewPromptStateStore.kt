package com.subhajit.mulberry.whatsnew

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class DataStoreWhatsNewPromptStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : WhatsNewPromptStateStore {

    override suspend fun get(): WhatsNewPromptState = dataStore.data.first().toState()

    override suspend fun updateAndGet(
        transform: (WhatsNewPromptState) -> WhatsNewPromptState
    ): WhatsNewPromptState {
        var updated = WhatsNewPromptState()
        dataStore.edit { preferences ->
            val current = preferences.toState()
            updated = transform(current)
            updateNullable(preferences, PreferenceStorage.whatsNewLastSeenVersionName, updated.lastSeenVersionName)
            updateNullable(preferences, PreferenceStorage.whatsNewPendingVersionName, updated.pendingVersionName)
            updateNullable(preferences, PreferenceStorage.whatsNewNextRetryAtMs, updated.nextRetryAtMs)
            preferences[PreferenceStorage.whatsNewRetryAttempt] = updated.retryAttempt
        }
        return updated
    }

    private fun Preferences.toState(): WhatsNewPromptState = WhatsNewPromptState(
        lastSeenVersionName = this[PreferenceStorage.whatsNewLastSeenVersionName],
        pendingVersionName = this[PreferenceStorage.whatsNewPendingVersionName],
        nextRetryAtMs = this[PreferenceStorage.whatsNewNextRetryAtMs],
        retryAttempt = this[PreferenceStorage.whatsNewRetryAttempt] ?: 0
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

