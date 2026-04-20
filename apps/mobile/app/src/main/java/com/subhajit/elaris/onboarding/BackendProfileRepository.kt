package com.subhajit.elaris.onboarding

import com.subhajit.elaris.data.bootstrap.SessionBootstrapRepository
import com.subhajit.elaris.network.ElarisApiService
import com.subhajit.elaris.network.ProfileRequest
import com.subhajit.elaris.network.toDomainBootstrap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackendProfileRepository @Inject constructor(
    private val apiService: ElarisApiService,
    private val sessionBootstrapRepository: SessionBootstrapRepository,
    private val onboardingDraftRepository: OnboardingDraftRepository
) : ProfileRepository {
    override suspend fun submitProfile(draft: UserProfileDraft): Result<Unit> = runCatching {
        require(draft.isComplete) { "All onboarding fields are required" }
        val bootstrap = apiService.updateProfile(
            ProfileRequest(
                displayName = draft.displayName,
                partnerDisplayName = draft.partnerDisplayName,
                anniversaryDate = draft.anniversaryDate
            )
        ).toDomainBootstrap()
        sessionBootstrapRepository.cacheBootstrap(bootstrap)
        onboardingDraftRepository.clear()
    }
}
