package com.subhajit.mulberry.onboarding

interface ProfileRepository {
    suspend fun submitProfile(draft: UserProfileDraft): Result<Unit>
}
