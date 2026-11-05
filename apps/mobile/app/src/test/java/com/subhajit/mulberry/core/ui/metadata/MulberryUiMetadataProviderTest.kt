package com.subhajit.mulberry.core.ui.metadata

import com.subhajit.mulberry.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MulberryUiMetadataProviderTest {

    @Test
    fun brandMetadataUsesMulberryResources() {
        val brand = MulberryUiMetadataProvider.brand

        assertEquals(R.string.app_name, brand.displayNameRes)
        assertEquals(R.string.app_tagline, brand.taglineRes)
        assertEquals(R.color.mulberry_primary, brand.primaryColorRes)
        assertEquals(R.drawable.brand_iconmark_color, brand.iconMarkDrawableRes)
        assertEquals(R.drawable.brand_iconmark_white, brand.iconMarkWhiteDrawableRes)
        assertEquals(R.drawable.brand_wordmark_color, brand.wordmarkDrawableRes)
    }

    @Test
    fun authLandingMetadataOffersGoogleThenApple() {
        val auth = MulberryUiMetadataProvider.authLanding

        assertEquals(R.drawable.auth_login_bg, auth.backgroundDrawableRes)
        assertEquals(R.string.auth_headline, auth.headlineRes)
        assertEquals(R.string.auth_subtitle, auth.subtitleRes)
        assertEquals(listOf(AuthProviderId.GOOGLE, AuthProviderId.APPLE), auth.providers.map { it.id })
        val (google, apple) = auth.providers
        assertEquals(R.string.auth_provider_google, google.labelRes)
        assertEquals(R.drawable.ic_google_logo, google.iconDrawableRes)
        assertEquals(R.string.auth_provider_apple, apple.labelRes)
        assertEquals(R.drawable.ic_apple_logo, apple.iconDrawableRes)
        assertTrue(auth.providers.all { it.enabled })
    }

    @Test
    fun authLandingUsesLightSystemBarIcons() {
        val systemBarStyle = MulberryUiMetadataProvider.authLanding.systemBarStyle

        assertEquals(0x00000000, systemBarStyle.statusBarColorArgb)
        assertEquals(0x00000000, systemBarStyle.navigationBarColorArgb)
        assertFalse(systemBarStyle.useDarkIcons)
    }
}
