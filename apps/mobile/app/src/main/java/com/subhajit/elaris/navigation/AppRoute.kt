package com.subhajit.elaris.navigation

sealed class AppRoute(val route: String) {
    data object Bootstrap : AppRoute("bootstrap")
    data object Welcome : AppRoute("welcome")
    data object Pairing : AppRoute("pairing")
    data object CanvasHome : AppRoute("canvas_home")
    data object CanvasSurface : AppRoute("canvas_surface")
    data object LockScreenPlaceholder : AppRoute("lockscreen_placeholder")
    data object Settings : AppRoute("settings")
}
