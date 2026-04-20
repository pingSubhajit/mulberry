package com.subhajit.elaris.network

import com.subhajit.elaris.data.bootstrap.AuthStatus
import com.subhajit.elaris.data.bootstrap.InviteStatus
import com.subhajit.elaris.data.bootstrap.PairingStatus
import com.subhajit.elaris.data.bootstrap.PendingInviteSummary
import com.subhajit.elaris.data.bootstrap.SessionBootstrapState

fun BootstrapResponse.toDomainBootstrap(): SessionBootstrapState = SessionBootstrapState(
    authStatus = AuthStatus.valueOf(authStatus),
    hasCompletedOnboarding = onboardingCompleted,
    hasWallpaperConfigured = hasWallpaperConfigured,
    userId = userId,
    userDisplayName = userDisplayName,
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
