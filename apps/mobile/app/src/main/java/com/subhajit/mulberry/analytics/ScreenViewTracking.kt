package com.subhajit.mulberry.analytics

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AnalyticsEntryPoint {
    fun appAnalytics(): AppAnalytics
    fun wallpaperSetupTracker(): WallpaperSetupTracker
}

fun Context.analyticsEntryPoint(): AnalyticsEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, AnalyticsEntryPoint::class.java)

/**
 * Records [event] once each time the calling screen appears, under [key]. Recomposition and
 * configuration changes don't count again; leaving the screen and coming back does.
 */
@Composable
fun TrackScreenView(key: String, event: () -> AnalyticsEvent) {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val analytics = remember(context) { context.analyticsEntryPoint().appAnalytics() }
    DisposableEffect(key) {
        if (VisibleScreenViews.add(key)) analytics.track(event())
        onDispose {
            if (context.findActivity()?.isChangingConfigurations != true) {
                VisibleScreenViews.remove(key)
            }
        }
    }
}

@Composable
fun TrackOnboardingStep(step: OnboardingStep, keyPrefix: String = "onboarding") {
    TrackScreenView(key = "$keyPrefix:${step.wireValue}") {
        AnalyticsEvent.OnboardingStepViewed(step)
    }
}

/** Screen views still on screen; kept across activity recreation so a rotation isn't a new view. */
private object VisibleScreenViews {
    private val keys = mutableSetOf<String>()

    fun add(key: String): Boolean = synchronized(keys) { keys.add(key) }

    fun remove(key: String) {
        synchronized(keys) { keys.remove(key) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
