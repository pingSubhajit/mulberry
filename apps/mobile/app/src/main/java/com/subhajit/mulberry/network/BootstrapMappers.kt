package com.subhajit.mulberry.network

import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.InviteStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.PendingInviteSummary
import com.subhajit.mulberry.data.bootstrap.PartnerWallpaperStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import com.subhajit.mulberry.drawing.render.CanvasStrokeRenderMode

fun BootstrapResponse.toDomainBootstrap(): SessionBootstrapState = SessionBootstrapState(
    authStatus = AuthStatus.valueOf(authStatus),
    hasCompletedOnboarding = onboardingCompleted,
    hasWallpaperConfigured = hasWallpaperConfigured,
    canvasStrokeRenderMode = CanvasStrokeRenderMode.fromRaw(canvasStrokeRenderMode),
    userId = userId,
    userEmail = userEmail,
    userPhotoUrl = userPhotoUrl,
    userDisplayName = userDisplayName,
    partnerPhotoUrl = partnerPhotoUrl,
    partnerDisplayName = partnerDisplayName,
    partnerWallpaperStatus = partnerWallpaperStatus?.let {
        PartnerWallpaperStatus(
            updatedAt = it.updatedAt,
            wallpaperSyncEnabled = it.wallpaperSyncEnabled,
            wallpaperSelectedOnHome = it.wallpaperSelectedOnHome,
            wallpaperSelectedOnLock = it.wallpaperSelectedOnLock,
            canSeeLatestDrawings = it.canSeeLatestDrawings,
            hasEverBeenAbleToSee = it.hasEverBeenAbleToSee
        )
    },
    anniversaryDate = anniversaryDate,
    partnerProfileNextUpdateAt = partnerProfileNextUpdateAt,
    pairedAt = pairedAt,
    currentStreakDays = currentStreakDays,
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
