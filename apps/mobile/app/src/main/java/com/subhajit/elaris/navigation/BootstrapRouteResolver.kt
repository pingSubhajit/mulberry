package com.subhajit.elaris.navigation

import com.subhajit.elaris.data.bootstrap.PairingStatus
import com.subhajit.elaris.data.bootstrap.SessionBootstrapState
import javax.inject.Inject

class BootstrapRouteResolver @Inject constructor() {
    fun resolve(state: SessionBootstrapState): AppRoute = when {
        !state.hasCompletedOnboarding -> AppRoute.Welcome
        state.pairingStatus == PairingStatus.PAIRED_PLACEHOLDER -> AppRoute.CanvasHome
        else -> AppRoute.Pairing
    }
}
