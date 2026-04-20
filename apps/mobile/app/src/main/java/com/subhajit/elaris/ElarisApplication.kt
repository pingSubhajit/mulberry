package com.subhajit.elaris

import android.app.Application
import com.subhajit.elaris.wallpaper.WallpaperCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ElarisApplication : Application() {
    @Inject lateinit var wallpaperCoordinator: WallpaperCoordinator

    override fun onCreate() {
        super.onCreate()
        wallpaperCoordinator
    }
}
