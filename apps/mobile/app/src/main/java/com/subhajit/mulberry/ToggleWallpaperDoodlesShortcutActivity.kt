package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.subhajit.mulberry.app.di.ApplicationScope
import com.subhajit.mulberry.app.shortcut.WallpaperDoodlesShortcutPublisher
import com.subhajit.mulberry.sync.PartnerWallpaperStatusReportCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ToggleWallpaperDoodlesShortcutActivity : ComponentActivity() {

    @Inject lateinit var wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository
    @Inject lateinit var wallpaperCoordinator: WallpaperCoordinator
    @Inject lateinit var partnerWallpaperStatusReportCoordinator: PartnerWallpaperStatusReportCoordinator
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)

        lifecycleScope.launch {
            val currentlyEnabled = wallpaperSyncSettingsRepository.enabled.first()
            val nextEnabled = !currentlyEnabled
            wallpaperSyncSettingsRepository.setEnabled(nextEnabled)
            WallpaperDoodlesShortcutPublisher.publish(this@ToggleWallpaperDoodlesShortcutActivity, nextEnabled)
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
            applicationScope.launch {
                partnerWallpaperStatusReportCoordinator.reportNow(
                    reason = "shortcut_wallpaper_doodles_toggle"
                )
            }
            finish()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
