package com.subhajit.mulberry.wallpaper

import android.content.Context
import java.io.File

object WallpaperFiles {
    private const val WALLPAPER_DIRECTORY = "wallpaper"
    private const val SNAPSHOT_FILE = "canvas_snapshot.png"
    private const val BACKGROUND_FILE = "background_image"

    fun wallpaperDirectory(context: Context): File =
        File(context.filesDir, WALLPAPER_DIRECTORY).apply { mkdirs() }

    fun snapshotFile(context: Context): File =
        File(wallpaperDirectory(context), SNAPSHOT_FILE)

    fun backgroundFile(context: Context): File =
        File(wallpaperDirectory(context), BACKGROUND_FILE)
}
