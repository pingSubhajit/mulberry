package com.subhajit.mulberry.navigation

import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
import javax.inject.Inject

class BootstrapRouteResolver @Inject constructor() {
    fun resolve(state: SessionBootstrapState): AppRoute = when {
        state.authStatus == com.subhajit.mulberry.data.bootstrap.AuthStatus.SIGNED_OUT ->
            AppRoute.AuthLanding
        state.pairingStatus == PairingStatus.INVITE_PENDING_ACCEPTANCE -> AppRoute.InviteAcceptance
        !state.hasCompletedOnboarding -> AppRoute.OnboardingName
        else -> AppRoute.CanvasHome
    }
}
