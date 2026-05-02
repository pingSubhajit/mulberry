package com.subhajit.mulberry.stickers

data class StickerPackSummary(
    val packKey: String,
    val packVersion: Int,
    val title: String,
    val description: String,
    val coverThumbnailUrl: String,
    val coverFullUrl: String,
    val sortOrder: Int,
    val featured: Boolean
)

data class StickerSummary(
    val stickerId: String,
    val thumbnailUrl: String,
    val fullUrl: String,
    val width: Int,
    val height: Int,
    val sortOrder: Int
)

data class StickerPackDetail(
    val packKey: String,
    val packVersion: Int,
    val title: String,
    val description: String,
    val coverThumbnailUrl: String,
    val coverFullUrl: String,
    val sortOrder: Int,
    val featured: Boolean,
    val stickers: List<StickerSummary>
)

data class StickerAssetUrl(
    val url: String,
    val expiresInSeconds: Int
)

