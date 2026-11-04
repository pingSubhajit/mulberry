package com.subhajit.mulberry.analytics

import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Follows the system live wallpaper picker: the `set_wallpaper` step is viewed when Mulberry opens
 * it, then completed or failed by whether Mulberry is the wallpaper once the app is back.
 */
@Singleton
class WallpaperSetupTracker @Inject constructor(
    private val analytics: AppAnalytics,
    private val wallpaperCoordinator: WallpaperCoordinator
) {
    private val awaitingPickerResult = AtomicBoolean(false)

    fun onPickerOpened() {
        awaitingPickerResult.set(true)
        track(WallpaperSetupStep.SET_WALLPAPER, WallpaperSetupOutcome.VIEWED)
    }

    /** Call when the app comes back to the foreground. */
    suspend fun onAppReturned() {
        if (!awaitingPickerResult.getAndSet(false)) return
        val isSet = isMulberrySelected() || run {
            // The system can take a moment to report the new wallpaper.
            delay(SELECTION_RECHECK_DELAY_MS)
            isMulberrySelected()
        }
        if (isSet) {
            track(WallpaperSetupStep.SET_WALLPAPER, WallpaperSetupOutcome.COMPLETED)
        } else {
            track(
                WallpaperSetupStep.SET_WALLPAPER,
                WallpaperSetupOutcome.FAILED,
                WallpaperSetupFailureReason.NOT_SET_IN_PICKER
            )
        }
    }

    fun track(
        step: WallpaperSetupStep,
        outcome: WallpaperSetupOutcome,
        failureReason: WallpaperSetupFailureReason? = null
    ) {
        analytics.track(AnalyticsEvent.WallpaperSetupStepChanged(step, outcome, failureReason))
    }

    private suspend fun isMulberrySelected(): Boolean {
        wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
        return wallpaperCoordinator.wallpaperStatus().first().isWallpaperSelected
    }

    private companion object {
        const val SELECTION_RECHECK_DELAY_MS = 500L
    }
}
