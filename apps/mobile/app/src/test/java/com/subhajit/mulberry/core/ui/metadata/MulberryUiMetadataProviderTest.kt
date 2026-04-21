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
    fun authLandingMetadataExposesOnlyGoogleProvider() {
        val auth = MulberryUiMetadataProvider.authLanding

        assertEquals(R.drawable.auth_login_bg, auth.backgroundDrawableRes)
        assertEquals(R.string.auth_headline, auth.headlineRes)
        assertEquals(R.string.auth_subtitle, auth.subtitleRes)
        assertEquals(1, auth.providers.size)
        assertEquals(AuthProviderId.GOOGLE, auth.providers.single().id)
        assertEquals(R.string.auth_provider_google, auth.providers.single().labelRes)
        assertEquals(R.drawable.ic_google_logo, auth.providers.single().iconDrawableRes)
        assertTrue(auth.providers.single().enabled)
    }

    @Test
    fun authLandingUsesLightSystemBarIcons() {
        val systemBarStyle = MulberryUiMetadataProvider.authLanding.systemBarStyle

        assertEquals(0x00000000, systemBarStyle.statusBarColorArgb)
        assertEquals(0x00000000, systemBarStyle.navigationBarColorArgb)
        assertFalse(systemBarStyle.useDarkIcons)
    }
}
