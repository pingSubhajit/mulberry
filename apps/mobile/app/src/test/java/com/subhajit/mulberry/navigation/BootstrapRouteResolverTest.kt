package com.subhajit.mulberry.navigation

import com.subhajit.mulberry.data.bootstrap.AuthStatus
import com.subhajit.mulberry.data.bootstrap.PairingStatus
import com.subhajit.mulberry.data.bootstrap.SessionBootstrapState
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
    fun `signed in completed onboarding user routes to wallpaper setup first`() {
        val state = SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN,
            hasCompletedOnboarding = true,
            pairingStatus = PairingStatus.UNPAIRED
        )

        assertEquals(AppRoute.OnboardingWallpaper, resolver.resolve(state))
    }

    @Test
    fun `signed in unpaired user with wallpaper configured routes to home`() {
        val state = SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN,
            hasCompletedOnboarding = true,
            hasWallpaperConfigured = true,
            pairingStatus = PairingStatus.UNPAIRED
        )

        assertEquals(AppRoute.CanvasHome, resolver.resolve(state))
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
    fun `invite pending user routes to invite acceptance before onboarding completion`() {
        val state = SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN,
            hasCompletedOnboarding = false,
            pairingStatus = PairingStatus.INVITE_PENDING_ACCEPTANCE
        )

        assertEquals(AppRoute.InviteAcceptance, resolver.resolve(state))
    }

    @Test
    fun `paired user routes to home`() {
        val state = SessionBootstrapState(
            authStatus = AuthStatus.SIGNED_IN,
            hasCompletedOnboarding = true,
            hasWallpaperConfigured = true,
            pairingStatus = PairingStatus.PAIRED
        )

        assertEquals(AppRoute.CanvasHome, resolver.resolve(state))
    }
}
