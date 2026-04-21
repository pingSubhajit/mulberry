package com.subhajit.mulberry.network

data class GoogleAuthRequest(
    val idToken: String
)

data class RefreshRequest(
    val refreshToken: String
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val bootstrapState: BootstrapResponse
)

data class BootstrapResponse(
    val authStatus: String,
    val onboardingCompleted: Boolean,
    val hasWallpaperConfigured: Boolean = false,
    val userId: String?,
    val userDisplayName: String?,
    val partnerDisplayName: String?,
    val anniversaryDate: String?,
    val pairingStatus: String,
    val pairSessionId: String?,
    val invite: InviteResponse?
)

data class InviteResponse(
    val inviteId: String,
    val code: String,
    val inviterDisplayName: String,
    val recipientDisplayName: String,
    val status: String
)

data class ProfileRequest(
    val displayName: String,
    val partnerDisplayName: String,
    val anniversaryDate: String
)

data class CreateInviteResponse(
    val inviteId: String,
    val code: String,
    val expiresAt: String
)

data class RedeemInviteRequest(
    val code: String
)

data class RedeemInviteResponse(
    val inviteId: String,
    val inviterDisplayName: String,
    val recipientDisplayName: String,
    val code: String,
    val status: String,
    val bootstrapState: BootstrapResponse
)

data class AcceptInviteResponse(
    val pairSessionId: String,
    val bootstrapState: BootstrapResponse
)
