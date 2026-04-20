package com.subhajit.elaris.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.subhajit.elaris.app.bootstrap.BootstrapRoute
import com.subhajit.elaris.auth.AuthLandingRoute
import com.subhajit.elaris.home.CanvasHomeRoute
import com.subhajit.elaris.home.CanvasSurfaceRoute
import com.subhajit.elaris.home.LockScreenPlaceholderRoute
import com.subhajit.elaris.onboarding.OnboardingDetailsRoute
import com.subhajit.elaris.onboarding.OnboardingNameRoute
import com.subhajit.elaris.pairing.InviteAcceptanceRoute
import com.subhajit.elaris.pairing.InviteCodeEntryRoute
import com.subhajit.elaris.pairing.PairingHubRoute
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

        composable(AppRoute.AuthLanding.route) {
            AuthLandingRoute(
                onNavigateToBootstrap = {
                    navController.navigate(AppRoute.Bootstrap.route) {
                        popUpTo(AppRoute.AuthLanding.route) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(AppRoute.OnboardingName.route) {
            OnboardingNameRoute(
                onNavigateToDetails = {
                    navController.navigate(AppRoute.OnboardingDetails.route)
                },
                onNavigateToCodeEntry = {
                    navController.navigate(AppRoute.InviteCodeEntry.route)
                }
            )
        }

        composable(AppRoute.OnboardingDetails.route) {
            OnboardingDetailsRoute(
                onNavigateToBootstrap = {
                    navController.navigate(AppRoute.Bootstrap.route) {
                        popUpTo(AppRoute.OnboardingName.route)
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(AppRoute.PairingHub.route) {
            PairingHubRoute(
                onNavigateToCodeEntry = {
                    navController.navigate(AppRoute.InviteCodeEntry.route)
                }
            )
        }

        composable(AppRoute.InviteCodeEntry.route) {
            InviteCodeEntryRoute(
                onNavigateToBootstrap = {
                    navController.navigate(AppRoute.Bootstrap.route) {
                        popUpTo(AppRoute.InviteCodeEntry.route) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(AppRoute.InviteAcceptance.route) {
            InviteAcceptanceRoute(
                onNavigateToBootstrap = {
                    navController.navigate(AppRoute.Bootstrap.route) {
                        popUpTo(AppRoute.InviteAcceptance.route) {
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
            LockScreenPlaceholderRoute(
                onNavigateBack = { navController.popBackStack() }
            )
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
