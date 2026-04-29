package com.subhajit.mulberry.review

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class DataStoreReviewPromptStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ReviewPromptStateStore {

    override suspend fun get(): ReviewPromptState = dataStore.data.first().toState()

    override suspend fun updateAndGet(
        transform: (ReviewPromptState) -> ReviewPromptState
    ): ReviewPromptState {
        var updated = ReviewPromptState()
        dataStore.edit { preferences ->
            val current = preferences.toState()
            updated = transform(current)
            preferences[PreferenceStorage.reviewMilestone3Reached] = updated.milestone3Reached
            preferences[PreferenceStorage.reviewAttemptCount] = updated.attemptCount
            updateNullable(
                preferences,
                PreferenceStorage.reviewLastAttemptAtMs,
                updated.lastAttemptAtMs
            )
            updateNullable(
                preferences,
                PreferenceStorage.reviewNextEligibleAtMs,
                updated.nextEligibleAtMs
            )
        }
        return updated
    }

    private fun Preferences.toState(): ReviewPromptState = ReviewPromptState(
        milestone3Reached = this[PreferenceStorage.reviewMilestone3Reached] ?: false,
        attemptCount = this[PreferenceStorage.reviewAttemptCount] ?: 0,
        lastAttemptAtMs = this[PreferenceStorage.reviewLastAttemptAtMs],
        nextEligibleAtMs = this[PreferenceStorage.reviewNextEligibleAtMs]
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

