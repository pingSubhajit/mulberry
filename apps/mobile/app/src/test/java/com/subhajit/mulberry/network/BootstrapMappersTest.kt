package com.subhajit.mulberry.network

import com.subhajit.mulberry.auth.SignInMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapMappersTest {
    @Test
    fun `sign-in methods come from the server, ignoring ones this build doesn't know`() {
        val state = response(signInMethods = listOf("apple", "google", "passkey")).toDomainBootstrap()

        assertEquals(setOf(SignInMethod.APPLE, SignInMethod.GOOGLE), state.signInMethods)
    }

    @Test
    fun `a server that doesn't list sign-in methods means Google`() {
        assertEquals(setOf(SignInMethod.GOOGLE), response(signInMethods = null).toDomainBootstrap().signInMethods)
    }

    private fun response(signInMethods: List<String>?) = BootstrapResponse(
        authStatus = "SIGNED_IN",
        onboardingCompleted = true,
        userId = "user-1",
        userEmail = "person@example.test",
        signInMethods = signInMethods,
        userPhotoUrl = null,
        userDisplayName = "Person",
        partnerPhotoUrl = null,
        partnerDisplayName = null,
        partnerWallpaperStatus = null,
        anniversaryDate = null,
        partnerProfileNextUpdateAt = null,
        pairedAt = null,
        pairingStatus = "UNPAIRED",
        pairSessionId = null,
        invite = null
    )
}
