package com.subhajit.mulberry.data.bootstrap

enum class AuthStatus(val displayName: String) {
    SIGNED_OUT("Signed Out"),
    SIGNED_IN("Signed In"),
    REFRESHING("Refreshing")
}

enum class PairingStatus(val displayName: String) {
    UNPAIRED("Unpaired"),
    INVITE_PENDING_ACCEPTANCE("Invite Pending Acceptance"),
    PAIRED("Paired")
}

enum class InviteStatus(val displayName: String) {
    PENDING("Pending"),
    REDEEMED("Redeemed"),
    ACCEPTED("Accepted"),
    DECLINED("Declined"),
    EXPIRED("Expired")
}

enum class CanvasMode(val displayName: String) {
    SHARED("Shared"),
    DEDICATED("Dedicated")
}

data class PendingInviteSummary(
    val inviteId: String,
    val code: String,
    val inviterDisplayName: String,
    val recipientDisplayName: String,
    val status: InviteStatus
)

data class AppSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String
)

data class SessionBootstrapState(
    val authStatus: AuthStatus = AuthStatus.SIGNED_OUT,
    val hasCompletedOnboarding: Boolean = false,
    val hasWallpaperConfigured: Boolean = false,
    val userId: String? = null,
    val userEmail: String? = null,
    val userPhotoUrl: String? = null,
    val userDisplayName: String? = null,
    val partnerUserId: String? = null,
    val partnerPhotoUrl: String? = null,
    val partnerDisplayName: String? = null,
    val anniversaryDate: String? = null,
    val partnerProfileNextUpdateAt: String? = null,
    val pairedAt: String? = null,
    val currentStreakDays: Int = 0,
    val pairingStatus: PairingStatus = PairingStatus.UNPAIRED,
    val pairSessionId: String? = null,
    val pendingInvite: PendingInviteSummary? = null,
    val canvasMode: CanvasMode = CanvasMode.SHARED,
    val canvasModeNextToggleAt: String? = null,
    val dedicatedCanvasAvailable: Boolean = false
)
