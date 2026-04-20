package com.subhajit.elaris.navigation

import com.subhajit.elaris.data.bootstrap.AuthStatus
import com.subhajit.elaris.data.bootstrap.PairingStatus
import com.subhajit.elaris.data.bootstrap.SessionBootstrapState
import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapRouteResolverTest {
    private val resolver = BootstrapRouteResolver()

    @Test
    fun `signed out user routes to auth landing`() {
        assertEquals(AppRoute.AuthLanding, resolver.resolve(SessionBootstrapState()))
    }

    @Test
    fun `signed in new user routes to onboarding`() {
        val state = SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN
        )

        assertEquals(AppRoute.OnboardingName, resolver.resolve(state))
    }

    @Test
    fun `signed in unpaired user routes to pairing hub`() {
        val state = SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN,
            hasCompletedOnboarding = true,
            pairingStatus = PairingStatus.UNPAIRED
        )

        assertEquals(AppRoute.PairingHub, resolver.resolve(state))
    }

    @Test
    fun `invite pending user routes to invite acceptance`() {
        val state = SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN,
            hasCompletedOnboarding = true,
            pairingStatus = PairingStatus.INVITE_PENDING_ACCEPTANCE
        )

        assertEquals(AppRoute.InviteAcceptance, resolver.resolve(state))
    }

    @Test
    fun `paired user routes to home`() {
        val state = SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN,
            hasCompletedOnboarding = true,
            pairingStatus = PairingStatus.PAIRED
        )

        assertEquals(AppRoute.CanvasHome, resolver.resolve(state))
    }
}
