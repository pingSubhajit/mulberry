package com.subhajit.elaris.navigation

import com.subhajit.elaris.data.bootstrap.PairingStatus
import com.subhajit.elaris.data.bootstrap.SessionBootstrapState
import com.subhajit.elaris.data.bootstrap.SessionDisplayState
import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapRouteResolverTest {
    private val resolver = BootstrapRouteResolver()

    @Test
    fun `fresh install routes to welcome`() {
        assertEquals(AppRoute.Welcome, resolver.resolve(SessionBootstrapState()))
    }

    @Test
    fun `completed onboarding without pairing routes to pairing`() {
        val state = SessionBootstrapState(
            hasCompletedOnboarding = true,
            pairingStatus = PairingStatus.UNPAIRED,
            sessionDisplayState = SessionDisplayState.EMPTY
        )

        assertEquals(AppRoute.Pairing, resolver.resolve(state))
    }

    @Test
    fun `placeholder paired state routes to home`() {
        val state = SessionBootstrapState(
            hasCompletedOnboarding = true,
            pairingStatus = PairingStatus.PAIRED_PLACEHOLDER,
            sessionDisplayState = SessionDisplayState.PLACEHOLDER_SESSION
        )

        assertEquals(AppRoute.CanvasHome, resolver.resolve(state))
    }
}
