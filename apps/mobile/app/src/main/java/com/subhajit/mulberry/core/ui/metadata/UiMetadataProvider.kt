package com.subhajit.mulberry.core.ui.metadata

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.subhajit.mulberry.R

interface UiMetadataProvider {
    val brand: BrandMetadata
    val authLanding: AuthLandingMetadata
}

data class BrandMetadata(
    @StringRes val displayNameRes: Int,
    @StringRes val taglineRes: Int,
    @ColorRes val primaryColorRes: Int,
    @DrawableRes val iconMarkDrawableRes: Int,
    @DrawableRes val iconMarkWhiteDrawableRes: Int,
    @DrawableRes val wordmarkDrawableRes: Int
)

data class AuthLandingMetadata(
    @DrawableRes val backgroundDrawableRes: Int,
    @StringRes val headlineRes: Int,
    @StringRes val subtitleRes: Int,
    val providers: List<AuthProviderMetadata>,
    val systemBarStyle: AppSystemBarStyle
)

data class AuthProviderMetadata(
    val id: AuthProviderId,
    @StringRes val labelRes: Int,
    @DrawableRes val iconDrawableRes: Int,
    val enabled: Boolean
)

enum class AuthProviderId {
    GOOGLE
}

data class AppSystemBarStyle(
    val statusBarColorArgb: Long,
    val navigationBarColorArgb: Long,
    val useDarkIcons: Boolean
)

object MulberryUiMetadataProvider : UiMetadataProvider {
    override val brand = BrandMetadata(
        displayNameRes = R.string.app_name,
        taglineRes = R.string.app_tagline,
        primaryColorRes = R.color.mulberry_primary,
        iconMarkDrawableRes = R.drawable.brand_iconmark_color,
        iconMarkWhiteDrawableRes = R.drawable.brand_iconmark_white,
        wordmarkDrawableRes = R.drawable.brand_wordmark_color
    )

    override val authLanding = AuthLandingMetadata(
        backgroundDrawableRes = R.drawable.auth_login_bg,
        headlineRes = R.string.auth_headline,
        subtitleRes = R.string.auth_subtitle,
        providers = listOf(
            AuthProviderMetadata(
                id = AuthProviderId.GOOGLE,
                labelRes = R.string.auth_provider_google,
                iconDrawableRes = R.drawable.ic_google_logo,
                enabled = true
            )
        ),
        systemBarStyle = AppSystemBarStyle(
            statusBarColorArgb = 0x00000000,
            navigationBarColorArgb = 0x00000000,
            useDarkIcons = false
        )
    )
}
