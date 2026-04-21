package com.subhajit.mulberry.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.subhajit.mulberry.app.bootstrap.BootstrapRoute
import com.subhajit.mulberry.app.bootstrap.ReleaseStartupGateAfterFirstFrame
import com.subhajit.mulberry.auth.AuthLandingRoute
import com.subhajit.mulberry.home.CanvasHomeRoute
import com.subhajit.mulberry.home.CanvasSurfaceRoute
import com.subhajit.mulberry.home.LockScreenPlaceholderRoute
import com.subhajit.mulberry.onboarding.OnboardingDetailsRoute
import com.subhajit.mulberry.onboarding.OnboardingNameRoute
import com.subhajit.mulberry.pairing.InviteAcceptanceRoute
import com.subhajit.mulberry.pairing.InviteCodeEntryRoute
import com.subhajit.mulberry.pairing.PairingHubRoute
import com.subhajit.mulberry.settings.SettingsRoute

@Composable
fun MulberryNavHost(
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
            ReleaseStartupGateAfterFirstFrame()
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
            ReleaseStartupGateAfterFirstFrame()
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
            ReleaseStartupGateAfterFirstFrame()
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
            ReleaseStartupGateAfterFirstFrame()
            PairingHubRoute(
                onNavigateToCodeEntry = {
                    navController.navigate(AppRoute.InviteCodeEntry.route)
                }
            )
        }

        composable(AppRoute.InviteCodeEntry.route) {
            ReleaseStartupGateAfterFirstFrame()
            InviteCodeEntryRoute(
                onNavigateBack = {
                    navController.popBackStack()
                },
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
            ReleaseStartupGateAfterFirstFrame()
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
            ReleaseStartupGateAfterFirstFrame()
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
            ReleaseStartupGateAfterFirstFrame()
            CanvasSurfaceRoute()
        }

        composable(AppRoute.LockScreenPlaceholder.route) {
            ReleaseStartupGateAfterFirstFrame()
            LockScreenPlaceholderRoute(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(AppRoute.Settings.route) {
            ReleaseStartupGateAfterFirstFrame()
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
