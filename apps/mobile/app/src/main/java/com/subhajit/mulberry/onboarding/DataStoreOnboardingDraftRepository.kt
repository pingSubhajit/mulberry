package com.subhajit.mulberry.onboarding

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class DataStoreOnboardingDraftRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : OnboardingDraftRepository {
    override val draft: Flow<UserProfileDraft> = dataStore.data.map { preferences ->
        UserProfileDraft(
            displayName = preferences[PreferenceStorage.onboardingDraftDisplayName].orEmpty(),
            partnerDisplayName =
                preferences[PreferenceStorage.onboardingDraftPartnerDisplayName].orEmpty(),
            anniversaryDate = preferences[PreferenceStorage.onboardingDraftAnniversaryDate].orEmpty()
        )
    }.distinctUntilChanged()

    override suspend fun updateDisplayName(displayName: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.onboardingDraftDisplayName] = displayName
        }
    }

    override suspend fun updatePartnerDetails(partnerDisplayName: String, anniversaryDate: String) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.onboardingDraftPartnerDisplayName] = partnerDisplayName
            preferences[PreferenceStorage.onboardingDraftAnniversaryDate] = anniversaryDate
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.onboardingDraftDisplayName)
            preferences.remove(PreferenceStorage.onboardingDraftPartnerDisplayName)
            preferences.remove(PreferenceStorage.onboardingDraftAnniversaryDate)
        }
    }
}
