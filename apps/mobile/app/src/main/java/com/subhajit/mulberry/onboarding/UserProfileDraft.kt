package com.subhajit.mulberry.onboarding

data class UserProfileDraft(
    val displayName: String = "",
    val partnerDisplayName: String = "",
    val anniversaryDate: String = ""
) {
    val isStepOneValid: Boolean
        get() = displayName.isNotBlank()

    val isComplete: Boolean
        get() = displayName.isNotBlank() &&
            partnerDisplayName.isNotBlank() &&
            anniversaryDate.isNotBlank()
}
