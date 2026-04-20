package com.subhajit.elaris.data.bootstrap

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
    val userDisplayName: String? = null,
    val partnerDisplayName: String? = null,
    val anniversaryDate: String? = null,
    val pairingStatus: PairingStatus = PairingStatus.UNPAIRED,
    val pairSessionId: String? = null,
    val pendingInvite: PendingInviteSummary? = null
)
