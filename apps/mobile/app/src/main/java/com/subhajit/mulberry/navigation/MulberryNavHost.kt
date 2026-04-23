package com.subhajit.mulberry.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.subhajit.mulberry.app.shortcut.AppShortcutActionController
import com.subhajit.mulberry.app.bootstrap.BootstrapRoute
import com.subhajit.mulberry.app.bootstrap.ReleaseStartupGateAfterFirstFrame
import com.subhajit.mulberry.auth.AuthLandingRoute
import com.subhajit.mulberry.home.CanvasHomeRoute
import com.subhajit.mulberry.home.CanvasSurfaceRoute
import com.subhajit.mulberry.home.LockScreenPlaceholderRoute
import com.subhajit.mulberry.onboarding.OnboardingDetailsRoute
import com.subhajit.mulberry.onboarding.OnboardingNameRoute
import com.subhajit.mulberry.onboarding.OnboardingWallpaperRoute
import com.subhajit.mulberry.pairing.InviteAcceptanceRoute
import com.subhajit.mulberry.pairing.InviteCodeEntryRoute
import com.subhajit.mulberry.pairing.PairingHubRoute
import com.subhajit.mulberry.settings.SettingsRoute

@Composable
fun MulberryNavHost(
    navController: NavHostController = rememberNavController()
) {
    val shortcutAction by AppShortcutActionController.pendingAction.collectAsStateWithLifecycle()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    LaunchedEffect(shortcutAction, currentRoute) {
        val action = shortcutAction ?: return@LaunchedEffect
        when (currentRoute) {
            AppRoute.CanvasSurface.route,
            AppRoute.LockScreenPlaceholder.route,
            AppRoute.Settings.route -> navController.navigate(AppRoute.CanvasHome.route) {
                launchSingleTop = true
                popUpTo(AppRoute.CanvasHome.route) {
                    inclusive = false
                }
            }

            AppRoute.AuthLanding.route,
            AppRoute.OnboardingName.route,
            AppRoute.OnboardingDetails.route,
            AppRoute.OnboardingWallpaper.route,
            AppRoute.PairingHub.route,
            AppRoute.InviteCodeEntry.route,
            AppRoute.InviteAcceptance.route -> AppShortcutActionController.markHandled(action)
        }
    }

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
                    navController.navigateToBootstrapClearingOnboarding(
                        fallbackPopRoute = AppRoute.OnboardingDetails
                    )
                }
            )
        }

        composable(AppRoute.OnboardingWallpaper.route) {
            ReleaseStartupGateAfterFirstFrame()
            OnboardingWallpaperRoute(
                onNavigateHome = {
                    navController.navigate(AppRoute.CanvasHome.route) {
                        popUpTo(AppRoute.OnboardingWallpaper.route) {
                            inclusive = true
                        }
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
                    navController.navigateToBootstrapClearingOnboarding(
                        fallbackPopRoute = AppRoute.InviteCodeEntry
                    )
                }
            )
        }

        composable(AppRoute.InviteAcceptance.route) {
            ReleaseStartupGateAfterFirstFrame()
            InviteAcceptanceRoute(
                onNavigateToBootstrap = {
                    navController.navigateToBootstrapClearingOnboarding(
                        fallbackPopRoute = AppRoute.InviteAcceptance
                    )
                }
            )
        }

        composable(AppRoute.CanvasHome.route) {
            ReleaseStartupGateAfterFirstFrame()
            CanvasHomeRoute(
                shortcutAction = shortcutAction,
                onShortcutActionHandled = AppShortcutActionController::markHandled,
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

private fun NavHostController.navigateToBootstrapClearingOnboarding(fallbackPopRoute: AppRoute) {
    val popRoute = if (hasBackStackEntry(AppRoute.OnboardingName)) {
        AppRoute.OnboardingName
    } else {
        fallbackPopRoute
    }

    navigate(AppRoute.Bootstrap.route) {
        popUpTo(popRoute.route) {
            inclusive = true
        }
        launchSingleTop = true
    }
}

private fun NavHostController.hasBackStackEntry(route: AppRoute): Boolean = try {
    getBackStackEntry(route.route)
    true
} catch (_: IllegalArgumentException) {
    false
}
