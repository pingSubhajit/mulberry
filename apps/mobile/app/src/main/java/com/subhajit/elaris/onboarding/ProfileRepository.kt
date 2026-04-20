package com.subhajit.elaris.onboarding

interface ProfileRepository {
    suspend fun submitProfile(draft: UserProfileDraft): Result<Unit>
}
