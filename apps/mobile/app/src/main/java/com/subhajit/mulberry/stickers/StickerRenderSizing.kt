package com.subhajit.mulberry.stickers

data class StickerRenderSizePx(
    val widthPx: Float,
    val heightPx: Float
)

/**
 * Resolves an aspect-preserving destination size for a sticker where [maxSizePx] is treated as the
 * maximum of width/height (i.e. the "long edge" after scaling).
 */
fun resolveStickerRenderSizePx(
    maxSizePx: Float,
    bitmapWidthPx: Int,
    bitmapHeightPx: Int
): StickerRenderSizePx {
    val safeMax = maxSizePx.coerceAtLeast(1f)
    val safeW = bitmapWidthPx.coerceAtLeast(1)
    val safeH = bitmapHeightPx.coerceAtLeast(1)
    val aspect = safeW.toFloat() / safeH.toFloat()
    return resolveStickerRenderSizePx(
        maxSizePx = safeMax,
        aspectRatio = aspect
    )
}

fun resolveStickerRenderSizePx(
    maxSizePx: Float,
    aspectRatio: Float
): StickerRenderSizePx {
    val safeMax = maxSizePx.coerceAtLeast(1f)
    val safeAspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    return if (safeAspect >= 1f) {
        StickerRenderSizePx(
            widthPx = safeMax,
            heightPx = safeMax / safeAspect
        )
    } else {
        StickerRenderSizePx(
            widthPx = safeMax * safeAspect,
            heightPx = safeMax
        )
    }
}

