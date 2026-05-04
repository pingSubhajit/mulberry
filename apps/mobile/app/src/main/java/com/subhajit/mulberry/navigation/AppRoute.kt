package com.subhajit.mulberry.navigation

sealed class AppRoute(val route: String) {
    data object Bootstrap : AppRoute("bootstrap")
    data object AuthLanding : AppRoute("auth_landing")
    data object OnboardingName : AppRoute("onboarding_name")
    data object OnboardingDetails : AppRoute("onboarding_details")
    data object OnboardingWallpaper : AppRoute("onboarding_wallpaper")
    data object PairingHub : AppRoute("pairing_hub")
    data object InviteCodeEntry : AppRoute("invite_code_entry")
    data object InviteAcceptance : AppRoute("invite_acceptance")
    data object CanvasHome : AppRoute("canvas_home")
    data object CanvasSurface : AppRoute("canvas_surface")
    data object LockScreenPlaceholder : AppRoute("lockscreen_placeholder")
    data object WallpaperCatalog : AppRoute("wallpaper_catalog")
    data object WallpaperHelp : AppRoute("wallpaper_help")
    data object PairingHelp : AppRoute("pairing_help")
    data object Streak : AppRoute("streak")
    data object Settings : AppRoute("settings")
}
