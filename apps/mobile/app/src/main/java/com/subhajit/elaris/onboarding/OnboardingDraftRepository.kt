package com.subhajit.elaris.onboarding

import kotlinx.coroutines.flow.Flow

interface OnboardingDraftRepository {
    val draft: Flow<UserProfileDraft>

    suspend fun updateDisplayName(displayName: String)

    suspend fun updatePartnerDetails(partnerDisplayName: String, anniversaryDate: String)

    suspend fun clear()
}
