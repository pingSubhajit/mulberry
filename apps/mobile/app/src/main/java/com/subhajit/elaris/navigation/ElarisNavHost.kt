package com.subhajit.elaris.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.subhajit.elaris.app.bootstrap.BootstrapRoute
import com.subhajit.elaris.home.CanvasHomeRoute
import com.subhajit.elaris.home.CanvasSurfaceRoute
import com.subhajit.elaris.home.LockScreenPlaceholderRoute
import com.subhajit.elaris.onboarding.WelcomeRoute
import com.subhajit.elaris.pairing.PairingRoute
import com.subhajit.elaris.settings.SettingsRoute

@Composable
fun ElarisNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = AppRoute.Bootstrap.route
    ) {
        composable(AppRoute.Bootstrap.route) {
            BootstrapRoute(
                onRouteResolved = { route ->
                    navController.navigate(route.route) {
                        popUpTo(AppRoute.Bootstrap.route) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(AppRoute.Welcome.route) {
            WelcomeRoute(
                onNavigateToPairing = {
                    navController.navigate(AppRoute.Pairing.route) {
                        popUpTo(AppRoute.Welcome.route) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(AppRoute.Pairing.route) {
            PairingRoute(
                onNavigateToCanvasHome = {
                    navController.navigate(AppRoute.CanvasHome.route) {
                        popUpTo(AppRoute.Pairing.route) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(AppRoute.CanvasHome.route) {
            CanvasHomeRoute(
                onNavigateToCanvas = {
                    navController.navigate(AppRoute.CanvasSurface.route)
                },
                onNavigateToLockScreen = {
                    navController.navigate(AppRoute.LockScreenPlaceholder.route)
                },
                onNavigateToSettings = {
                    navController.navigate(AppRoute.Settings.route)
                }
            )
        }

        composable(AppRoute.CanvasSurface.route) {
            CanvasSurfaceRoute()
        }

        composable(AppRoute.LockScreenPlaceholder.route) {
            LockScreenPlaceholderRoute()
        }

        composable(AppRoute.Settings.route) {
            SettingsRoute(
                onNavigateBack = { navController.popBackStack() },
                onResetAppState = {
                    navController.navigate(AppRoute.Bootstrap.route) {
                        popUpTo(AppRoute.CanvasHome.route) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                },
                onNavigateHome = {
                    navController.popBackStack(AppRoute.CanvasHome.route, false)
                }
            )
        }
    }
}
