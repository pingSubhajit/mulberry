package com.subhajit.mulberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import com.subhajit.mulberry.wallpaper.WallpaperSyncSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class EnableWallpaperSyncActivity : ComponentActivity() {

    @Inject lateinit var wallpaperSyncSettingsRepository: WallpaperSyncSettingsRepository
    @Inject lateinit var wallpaperCoordinator: WallpaperCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        lifecycleScope.launch {
            wallpaperSyncSettingsRepository.setEnabled(true)
            wallpaperCoordinator.ensureSnapshotCurrent()
            wallpaperCoordinator.notifyWallpaperUpdatedIfSelected()
            finish()
        }
    }
}

