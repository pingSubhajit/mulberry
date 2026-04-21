package com.subhajit.mulberry

import android.app.Application
import com.subhajit.mulberry.wallpaper.WallpaperCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MulberryApplication : Application() {
    @Inject lateinit var wallpaperCoordinator: WallpaperCoordinator

    override fun onCreate() {
        super.onCreate()
        wallpaperCoordinator
    }
}
