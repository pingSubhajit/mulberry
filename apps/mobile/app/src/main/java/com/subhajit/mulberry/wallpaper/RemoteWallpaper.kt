package com.subhajit.mulberry.wallpaper

data class RemoteWallpaper(
    val id: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val previewUrl: String,
    val fullImageUrl: String,
    val width: Int,
    val height: Int,
    val dominantColor: String
)

data class WallpaperCatalogPage(
    val items: List<RemoteWallpaper>,
    val nextCursor: String?
)
