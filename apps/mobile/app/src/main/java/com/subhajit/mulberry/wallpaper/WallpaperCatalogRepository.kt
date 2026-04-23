package com.subhajit.mulberry.wallpaper

import com.subhajit.mulberry.network.MulberryApiService
import com.subhajit.mulberry.network.RemoteWallpaperResponse
import javax.inject.Inject
import javax.inject.Singleton

interface WallpaperCatalogRepository {
    suspend fun fetchPage(cursor: String?, limit: Int = 24): WallpaperCatalogPage
}

@Singleton
class BackendWallpaperCatalogRepository @Inject constructor(
    private val apiService: MulberryApiService
) : WallpaperCatalogRepository {
    override suspend fun fetchPage(cursor: String?, limit: Int): WallpaperCatalogPage {
        val response = apiService.getWallpapers(cursor = cursor, limit = limit)
        return WallpaperCatalogPage(
            items = response.items.map(RemoteWallpaperResponse::toDomain),
            nextCursor = response.nextCursor
        )
    }
}

private fun RemoteWallpaperResponse.toDomain(): RemoteWallpaper =
    RemoteWallpaper(
        id = id,
        title = title,
        description = description,
        thumbnailUrl = thumbnailUrl,
        previewUrl = previewUrl,
        fullImageUrl = fullImageUrl,
        width = width,
        height = height,
        dominantColor = dominantColor
    )
