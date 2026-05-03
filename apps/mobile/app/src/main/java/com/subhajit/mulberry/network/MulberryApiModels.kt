package com.subhajit.mulberry.network

import com.google.gson.JsonObject

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
    val partnerProfileNextUpdateAt: String?,
    val pairedAt: String?,
    val currentStreakDays: Int = 0,
    val pairingStatus: String,
    val pairSessionId: String?,
    val invite: InviteResponse?
)

data class StreakWeekDayResponse(
    val day: String,
    val hasActivity: Boolean
)

data class StreakResponse(
    val today: String,
    val currentStreakDays: Int,
    val previousStreakDays: Int,
    val hasActivityToday: Boolean,
    val lastActivityDay: String?,
    val week: List<StreakWeekDayResponse>
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

data class DisplayNameRequest(
    val displayName: String
)

data class PartnerProfileRequest(
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

data class CanvasOperationBatchRequest(
    val batchId: String,
    val operations: List<ClientCanvasOperationRequest>,
    val clientCreatedAt: String
)

data class ClientCanvasOperationRequest(
    val clientOperationId: String,
    val type: String,
    val strokeId: String?,
    val payload: JsonObject,
    val clientCreatedAt: String,
    val clientLocalDate: String? = null
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
    val strokes: List<CanvasSnapshotStroke> = emptyList(),
    val textElements: List<CanvasSnapshotTextElement> = emptyList(),
    val elements: List<CanvasSnapshotElement>? = null
)

data class CanvasSnapshotStroke(
    val id: String,
    val colorArgb: Long,
    val width: Float,
    val createdAt: Long,
    val points: List<CanvasPointPayload> = emptyList(),
    val finished: Boolean = true
)

data class CanvasSnapshotTextElement(
    val id: String,
    val text: String,
    val createdAt: Long,
    val center: CanvasPointPayload,
    val rotationRad: Float = 0f,
    val scale: Float = 1f,
    val boxWidth: Float,
    val colorArgb: Long,
    val backgroundPillEnabled: Boolean = false,
    val font: String = "POPPINS",
    val alignment: String = "CENTER"
)

data class CanvasSnapshotElement(
    val kind: String,
    val id: String,
    val createdAt: Long,
    val center: CanvasPointPayload,
    val rotationRad: Float = 0f,
    val scale: Float = 1f,
    // TEXT
    val text: String? = null,
    val boxWidth: Float? = null,
    val colorArgb: Long? = null,
    val backgroundPillEnabled: Boolean? = null,
    val font: String? = null,
    val alignment: String? = null,
    // STICKER
    val packKey: String? = null,
    val packVersion: Int? = null,
    val stickerId: String? = null
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

data class DebugActionResponse(
    val ok: Boolean
)

data class WallpaperCatalogResponse(
    val items: List<RemoteWallpaperResponse> = emptyList(),
    val nextCursor: String?
)

data class RemoteWallpaperResponse(
    val id: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val previewUrl: String,
    val fullImageUrl: String,
    val width: Int,
    val height: Int,
    val dominantColor: String
)

data class StickerPackListResponse(
    val items: List<StickerPackSummaryResponse> = emptyList()
)

data class StickerPackSummaryResponse(
    val packKey: String,
    val packVersion: Int,
    val title: String,
    val description: String,
    val coverThumbnailUrl: String,
    val coverFullUrl: String,
    val sortOrder: Int,
    val featured: Boolean
)

data class StickerPackDetailResponse(
    val packKey: String,
    val packVersion: Int,
    val title: String,
    val description: String,
    val coverThumbnailUrl: String,
    val coverFullUrl: String,
    val sortOrder: Int,
    val featured: Boolean,
    val stickers: List<StickerSummaryResponse> = emptyList()
)

data class StickerSummaryResponse(
    val stickerId: String,
    val thumbnailUrl: String,
    val fullUrl: String,
    val width: Int,
    val height: Int,
    val sortOrder: Int
)

data class StickerAssetUrlResponse(
    val url: String,
    val expiresInSeconds: Int
)
