package com.subhajit.mulberry.stickers

import com.subhajit.mulberry.network.MulberryApiService
import javax.inject.Inject
import javax.inject.Singleton

interface StickerCatalogRepository {
    suspend fun fetchPacks(): List<StickerPackSummary>
    suspend fun fetchPackDetail(packKey: String, version: Int? = null): StickerPackDetail
    suspend fun fetchStickerAssetUrl(
        packKey: String,
        version: Int,
        stickerId: String,
        variant: StickerAssetVariant
    ): StickerAssetUrl
}

enum class StickerAssetVariant {
    THUMBNAIL,
    FULL
}

@Singleton
class BackendStickerCatalogRepository @Inject constructor(
    private val apiService: MulberryApiService
) : StickerCatalogRepository {
    override suspend fun fetchPacks(): List<StickerPackSummary> {
        val response = apiService.getStickerPacks()
        return response.items.map { pack ->
            StickerPackSummary(
                packKey = pack.packKey,
                packVersion = pack.packVersion,
                title = pack.title,
                description = pack.description,
                coverThumbnailUrl = pack.coverThumbnailUrl,
                coverFullUrl = pack.coverFullUrl,
                sortOrder = pack.sortOrder,
                featured = pack.featured
            )
        }
    }

    override suspend fun fetchPackDetail(packKey: String, version: Int?): StickerPackDetail {
        val response = apiService.getStickerPackDetail(packKey = packKey, version = version)
        return StickerPackDetail(
            packKey = response.packKey,
            packVersion = response.packVersion,
            title = response.title,
            description = response.description,
            coverThumbnailUrl = response.coverThumbnailUrl,
            coverFullUrl = response.coverFullUrl,
            sortOrder = response.sortOrder,
            featured = response.featured,
            stickers = response.stickers.map { sticker ->
                StickerSummary(
                    stickerId = sticker.stickerId,
                    thumbnailUrl = sticker.thumbnailUrl,
                    fullUrl = sticker.fullUrl,
                    width = sticker.width,
                    height = sticker.height,
                    sortOrder = sticker.sortOrder
                )
            }
        )
    }

    override suspend fun fetchStickerAssetUrl(
        packKey: String,
        version: Int,
        stickerId: String,
        variant: StickerAssetVariant
    ): StickerAssetUrl {
        val response = apiService.getStickerAssetUrl(
            packKey = packKey,
            version = version,
            stickerId = stickerId,
            variant = when (variant) {
                StickerAssetVariant.THUMBNAIL -> "thumbnail"
                StickerAssetVariant.FULL -> "full"
            }
        )
        return StickerAssetUrl(url = response.url, expiresInSeconds = response.expiresInSeconds)
    }
}

