package com.subhajit.mulberry.data.bootstrap

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import com.subhajit.mulberry.core.config.AppConfig
import com.subhajit.mulberry.core.data.PreferenceStorage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class DataStoreSessionBootstrapRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val appConfig: AppConfig
) : SessionBootstrapRepository {

    override val state: Flow<SessionBootstrapState> = dataStore.data
        .map { preferences ->
            val inviteId = preferences[PreferenceStorage.pendingInviteId]
            val inviteCode = preferences[PreferenceStorage.pendingInviteCode]
            val inviterDisplayName = preferences[PreferenceStorage.pendingInviterDisplayName]
            val recipientDisplayName = preferences[PreferenceStorage.pendingRecipientDisplayName]
            val inviteStatus = preferences[PreferenceStorage.pendingInviteStatus]
                ?.let(InviteStatus::valueOf)

            SessionBootstrapState(
                authStatus = preferences[PreferenceStorage.authStatus]
                    ?.let(AuthStatus::valueOf)
                    ?: AuthStatus.SIGNED_OUT,
                hasCompletedOnboarding = preferences[PreferenceStorage.onboardingCompleted] ?: false,
                hasWallpaperConfigured = preferences[PreferenceStorage.wallpaperConfigured] ?: false,
                userId = preferences[PreferenceStorage.sessionUserId],
                userEmail = preferences[PreferenceStorage.userEmail],
                userPhotoUrl = preferences[PreferenceStorage.userPhotoUrl],
                userDisplayName = preferences[PreferenceStorage.userDisplayName],
                partnerPhotoUrl = preferences[PreferenceStorage.partnerPhotoUrl],
                partnerDisplayName = preferences[PreferenceStorage.partnerDisplayName],
                anniversaryDate = preferences[PreferenceStorage.anniversaryDate],
                partnerProfileNextUpdateAt = preferences[PreferenceStorage.partnerProfileNextUpdateAt],
                pairedAt = preferences[PreferenceStorage.pairedAt],
                currentStreakDays = preferences[PreferenceStorage.currentStreakDays] ?: 0,
                pairingStatus = preferences[PreferenceStorage.pairingStatus]
                    ?.let(::parsePairingStatus)
                    ?: PairingStatus.UNPAIRED,
                pairSessionId = preferences[PreferenceStorage.pairSessionId],
                pendingInvite = if (
                    inviteId != null &&
                    inviteCode != null &&
                    inviterDisplayName != null &&
                    recipientDisplayName != null &&
                    inviteStatus != null
                ) {
                    PendingInviteSummary(
                        inviteId = inviteId,
                        code = inviteCode,
                        inviterDisplayName = inviterDisplayName,
                        recipientDisplayName = recipientDisplayName,
                        status = inviteStatus
                    )
                } else {
                    null
                }
            )
        }
        .distinctUntilChanged()

    override val session: Flow<AppSession?> = dataStore.data
        .map { preferences ->
            val accessToken = preferences[PreferenceStorage.accessToken]
            val refreshToken = preferences[PreferenceStorage.refreshToken]
            val userId = preferences[PreferenceStorage.sessionUserId]
            if (accessToken != null && refreshToken != null && userId != null) {
                AppSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    userId = userId
                )
            } else {
                null
            }
        }
        .distinctUntilChanged()

    override suspend fun getCurrentSession(): AppSession? = session.first()

    override suspend fun cacheBootstrap(state: SessionBootstrapState) {
        dataStore.edit { preferences ->
            val localWallpaperConfigured =
                preferences[PreferenceStorage.wallpaperConfigured] ?: false
            preferences[PreferenceStorage.authStatus] = state.authStatus.name
            preferences[PreferenceStorage.onboardingCompleted] = state.hasCompletedOnboarding
            preferences[PreferenceStorage.wallpaperConfigured] =
                state.hasWallpaperConfigured || localWallpaperConfigured
            updateNullable(preferences, PreferenceStorage.sessionUserId, state.userId)
            updateNullable(preferences, PreferenceStorage.userEmail, state.userEmail)
            updateNullable(preferences, PreferenceStorage.userPhotoUrl, state.userPhotoUrl)
            updateNullable(preferences, PreferenceStorage.userDisplayName, state.userDisplayName)
            updateNullable(preferences, PreferenceStorage.partnerPhotoUrl, state.partnerPhotoUrl)
            updateNullable(preferences, PreferenceStorage.partnerDisplayName, state.partnerDisplayName)
            updateNullable(preferences, PreferenceStorage.anniversaryDate, state.anniversaryDate)
            updateNullable(
                preferences,
                PreferenceStorage.partnerProfileNextUpdateAt,
                state.partnerProfileNextUpdateAt
            )
            updateNullable(preferences, PreferenceStorage.pairedAt, state.pairedAt)
            preferences[PreferenceStorage.currentStreakDays] = state.currentStreakDays
            preferences[PreferenceStorage.pairingStatus] = state.pairingStatus.name
            updateNullable(preferences, PreferenceStorage.pairSessionId, state.pairSessionId)

            if (state.pendingInvite == null) {
                preferences.remove(PreferenceStorage.pendingInviteId)
                preferences.remove(PreferenceStorage.pendingInviteCode)
                preferences.remove(PreferenceStorage.pendingInviterDisplayName)
                preferences.remove(PreferenceStorage.pendingRecipientDisplayName)
                preferences.remove(PreferenceStorage.pendingInviteStatus)
            } else {
                preferences[PreferenceStorage.pendingInviteId] = state.pendingInvite.inviteId
                preferences[PreferenceStorage.pendingInviteCode] = state.pendingInvite.code
                preferences[PreferenceStorage.pendingInviterDisplayName] =
                    state.pendingInvite.inviterDisplayName
                preferences[PreferenceStorage.pendingRecipientDisplayName] =
                    state.pendingInvite.recipientDisplayName
                preferences[PreferenceStorage.pendingInviteStatus] = state.pendingInvite.status.name
            }
        }
    }

    override suspend fun cacheSession(session: AppSession?) {
        dataStore.edit { preferences ->
            if (session == null) {
                preferences.remove(PreferenceStorage.accessToken)
                preferences.remove(PreferenceStorage.refreshToken)
                if (preferences[PreferenceStorage.authStatus] == AuthStatus.REFRESHING.name ||
                    preferences[PreferenceStorage.authStatus] == AuthStatus.SIGNED_IN.name
                ) {
                    preferences[PreferenceStorage.authStatus] = AuthStatus.SIGNED_OUT.name
                }
                preferences.remove(PreferenceStorage.sessionUserId)
            } else {
                preferences[PreferenceStorage.accessToken] = session.accessToken
                preferences[PreferenceStorage.refreshToken] = session.refreshToken
                preferences[PreferenceStorage.sessionUserId] = session.userId
                preferences[PreferenceStorage.authStatus] = AuthStatus.SIGNED_IN.name
            }
        }
    }

    override suspend fun setWallpaperConfigured(configured: Boolean) {
        dataStore.edit { preferences ->
            val alreadyConfigured = preferences[PreferenceStorage.wallpaperConfigured] ?: false
            preferences[PreferenceStorage.wallpaperConfigured] = alreadyConfigured || configured
        }
    }

    override suspend fun seedDemoSession() {
        if (!appConfig.enableDebugMenu) return

        cacheSession(
            AppSession(
                accessToken = "dev-access-token",
                refreshToken = "dev-refresh-token",
                userId = "dev-user-id"
            )
        )
        cacheBootstrap(
            SessionBootstrapState(
                authStatus = AuthStatus.SIGNED_IN,
                hasCompletedOnboarding = true,
                userId = "dev-user-id",
                userDisplayName = "Subhajit",
                partnerDisplayName = "Ankita",
                anniversaryDate = "2026-01-01",
                pairedAt = java.time.Instant.now().toString(),
                currentStreakDays = 1,
                pairingStatus = PairingStatus.PAIRED,
                pairSessionId = "dev-pair-session"
            )
        )
    }

    override suspend fun reset() {
        dataStore.edit { preferences ->
            preferences.remove(PreferenceStorage.authStatus)
            preferences.remove(PreferenceStorage.accessToken)
            preferences.remove(PreferenceStorage.refreshToken)
            preferences.remove(PreferenceStorage.sessionUserId)
            preferences.remove(PreferenceStorage.onboardingCompleted)
            preferences.remove(PreferenceStorage.userEmail)
            preferences.remove(PreferenceStorage.userPhotoUrl)
            preferences.remove(PreferenceStorage.userDisplayName)
            preferences.remove(PreferenceStorage.partnerPhotoUrl)
            preferences.remove(PreferenceStorage.partnerDisplayName)
            preferences.remove(PreferenceStorage.anniversaryDate)
            preferences.remove(PreferenceStorage.partnerProfileNextUpdateAt)
            preferences.remove(PreferenceStorage.pairingStatus)
            preferences.remove(PreferenceStorage.pairSessionId)
            preferences.remove(PreferenceStorage.pendingInviteId)
            preferences.remove(PreferenceStorage.pendingInviteCode)
            preferences.remove(PreferenceStorage.pendingInviterDisplayName)
            preferences.remove(PreferenceStorage.pendingRecipientDisplayName)
            preferences.remove(PreferenceStorage.pendingInviteStatus)
            preferences.remove(PreferenceStorage.pendingInboundInviteCode)
            preferences.remove(PreferenceStorage.pendingInboundInviteReceivedAtMs)
            preferences.remove(PreferenceStorage.pendingInboundInviteSource)
            preferences.remove(PreferenceStorage.pendingInboundInviteDismissedAtMs)
            preferences.remove(PreferenceStorage.installReferrerChecked)
            preferences.remove(PreferenceStorage.wallpaperConfigured)
            preferences.remove(PreferenceStorage.onboardingDraftDisplayName)
            preferences.remove(PreferenceStorage.onboardingDraftPartnerDisplayName)
            preferences.remove(PreferenceStorage.onboardingDraftAnniversaryDate)
        }
    }

    private fun updateNullable(
        preferences: MutablePreferences,
        key: Preferences.Key<String>,
        value: String?
    ) {
        if (value == null) {
            preferences.remove(key)
        } else {
            preferences[key] = value
        }
    }

    private fun parsePairingStatus(raw: String): PairingStatus = when (raw) {
        "PAIRED_PLACEHOLDER" -> PairingStatus.PAIRED
        else -> PairingStatus.valueOf(raw)
    }
}
