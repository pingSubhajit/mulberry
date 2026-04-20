package com.subhajit.elaris.data.bootstrap

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.subhajit.elaris.core.config.AppConfig
import com.subhajit.elaris.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class DataStoreSessionBootstrapRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val appConfig: AppConfig
) : SessionBootstrapRepository {

    override val state: Flow<SessionBootstrapState> = dataStore.data
        .map { preferences ->
            SessionBootstrapState(
                hasCompletedOnboarding = preferences[PreferenceStorage.onboardingCompleted] ?: false,
                pairingStatus = preferences[PreferenceStorage.pairingStatus]
                    ?.let(PairingStatus::valueOf)
                    ?: PairingStatus.UNPAIRED,
                hasWallpaperConfigured = preferences[PreferenceStorage.wallpaperConfigured] ?: false,
                sessionDisplayState = preferences[PreferenceStorage.sessionDisplayState]
                    ?.let(SessionDisplayState::valueOf)
                    ?: SessionDisplayState.EMPTY
            )
        }
        .distinctUntilChanged()

    override suspend fun completeOnboarding() {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.onboardingCompleted] = true
        }
    }

    override suspend fun setPairingStatus(status: PairingStatus) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.pairingStatus] = status.name
        }
    }

    override suspend fun setWallpaperConfigured(configured: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.wallpaperConfigured] = configured
        }
    }

    override suspend fun setSessionDisplayState(state: SessionDisplayState) {
        dataStore.edit { preferences ->
            preferences[PreferenceStorage.sessionDisplayState] = state.name
        }
    }

    override suspend fun seedDemoSession() {
        if (!appConfig.enableDebugMenu) return

        dataStore.edit { preferences ->
            preferences[PreferenceStorage.onboardingCompleted] = true
            preferences[PreferenceStorage.pairingStatus] = PairingStatus.PAIRED_PLACEHOLDER.name
            preferences[PreferenceStorage.sessionDisplayState] =
                SessionDisplayState.PLACEHOLDER_SESSION.name
        }
    }

    override suspend fun reset() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.onboardingCompleted)
            preferences.remove(PreferenceStorage.pairingStatus)
            preferences.remove(PreferenceStorage.wallpaperConfigured)
            preferences.remove(PreferenceStorage.sessionDisplayState)
        }
    }
}
