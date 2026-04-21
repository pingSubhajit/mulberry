package com.subhajit.mulberry.onboarding

import com.subhajit.mulberry.data.bootstrap.SessionBootstrapRepository
import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.ProfileRequest
import com.subhajit.mulberry.network.toDomainBootstrap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackendProfileRepository @Inject constructor(
    private val apiService: MulberryApiService,
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
