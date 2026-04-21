package com.subhajit.mulberry.network

import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.InviteStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.PendingInviteSummary
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState

fun BootstrapResponse.toDomainBootstrap(): SessionBootstrapState = SessionBootstrapState(
    authStatus = AuthStatus.valueOf(authStatus),
    hasCompletedOnboarding = onboardingCompleted,
    hasWallpaperConfigured = hasWallpaperConfigured,
    userId = userId,
    userEmail = userEmail,
    userPhotoUrl = userPhotoUrl,
    userDisplayName = userDisplayName,
    partnerPhotoUrl = partnerPhotoUrl,
    partnerDisplayName = partnerDisplayName,
    anniversaryDate = anniversaryDate,
    pairingStatus = PairingStatus.valueOf(pairingStatus),
    pairSessionId = pairSessionId,
    pendingInvite = invite?.let {
        PendingInviteSummary(
            inviteId = it.inviteId,
            code = it.code,
            inviterDisplayName = it.inviterDisplayName,
            recipientDisplayName = it.recipientDisplayName,
            status = InviteStatus.valueOf(it.status)
        )
    }
)
