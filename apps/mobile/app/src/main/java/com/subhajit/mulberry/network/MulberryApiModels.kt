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
    val userEmail: String?,
    val userPhotoUrl: String?,
    val userDisplayName: String?,
    val partnerPhotoUrl: String?,
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

data class CanvasOpsResponse(
    val operations: List<CanvasOperationEnvelopeResponse>
)

data class CanvasSnapshotResponse(
    val pairSessionId: String,
    val revision: Long,
    val snapshotRevision: Long = revision,
    val latestRevision: Long = revision,
    val snapshot: CanvasSnapshotPayload,
    val updatedAt: String?
)

data class CanvasSnapshotPayload(
    val strokes: List<CanvasSnapshotStroke> = emptyList()
)

data class CanvasSnapshotStroke(
    val id: String,
    val colorArgb: Long,
    val width: Float,
    val createdAt: Long,
    val points: List<CanvasPointPayload> = emptyList(),
    val finished: Boolean = true
)

data class CanvasOperationEnvelopeResponse(
    val clientOperationId: String,
    val actorUserId: String,
    val pairSessionId: String,
    val type: String,
    val strokeId: String?,
    val payload: com.google.gson.JsonObject?,
    val clientCreatedAt: String,
    val serverRevision: Long,
    val createdAt: String
)

data class CanvasPointPayload(
    val x: Float,
    val y: Float
)

data class RegisterFcmTokenRequest(
    val token: String,
    val platform: String = "ANDROID",
    val appEnvironment: String
)

data class UnregisterFcmTokenRequest(
    val token: String
)

data class DeviceTokenResponse(
    val id: String,
    val userId: String,
    val token: String,
    val platform: String,
    val appEnvironment: String,
    val lastSeenAt: String,
    val revokedAt: String?
)
