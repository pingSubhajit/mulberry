package com.subhajit.mulberry.widget.relationship

import kotlin.math.roundToInt

internal data class CompactRelationshipWidgetLayout(
    val hostWidthDp: Float,
    val hostHeightDp: Float,
    val squirrelWidthDp: Float,
    val squirrelHeightDp: Float,
    val squirrelStartMarginDp: Float,
    val squirrelTopMarginDp: Float,
    val donutSizeDp: Float,
    val donutEndMarginDp: Float,
    val donutBottomMarginDp: Float,
    val textWidthDp: Float,
    val textHeightDp: Float,
    val textEndMarginDp: Float,
    val textBottomMarginDp: Float
) {
    val clusterWidthDp: Float
        get() = textWidthDp

    val clusterHeightDp: Float
        get() = donutSizeDp + donutToTextGapDp + textHeightDp

    val textTopDp: Float
        get() = hostHeightDp - textBottomMarginDp - textHeightDp

    val donutTopDp: Float
        get() = textTopDp + donutBottomMarginDp - donutSizeDp

    val donutToTextGapDp: Float
        get() = -donutBottomMarginDp
}

internal object CompactRelationshipWidgetLayoutSpec {
    private const val FALLBACK_WIDTH_DP = 220
    private const val FALLBACK_HEIGHT_DP = 180
    private const val TEXT_ASPECT_RATIO = 70f / 168f
    private const val DONUT_TO_TEXT_GAP_DP = 2f

    fun calculate(widthDp: Int, heightDp: Int): CompactRelationshipWidgetLayout {
        val safeWidth = widthDp.takeIf { it > 0 }?.toFloat() ?: FALLBACK_WIDTH_DP.toFloat()
        val safeHeight = heightDp.takeIf { it > 0 }?.toFloat() ?: FALLBACK_HEIGHT_DP.toFloat()

        val textEndMargin = (safeWidth * 0.055f).coerceIn(12f, 18f)
        val textBottomMargin = (safeHeight * 0.02f).coerceIn(3f, 5f)
        val maxTextWidth = (safeWidth - textEndMargin - 14f).coerceAtLeast(128f)
        val textWidth = (safeWidth * 0.71f).coerceIn(146f, 208f).coerceAtMost(maxTextWidth)
        val textHeight = (textWidth * TEXT_ASPECT_RATIO).roundDp().coerceIn(62f, 88f)

        val donutSize = (textHeight * 0.72f).coerceIn(54f, 64f)
        val availableDonutTop = safeHeight - textBottomMargin - textHeight - DONUT_TO_TEXT_GAP_DP - donutSize
        val adjustedDonutSize = if (availableDonutTop >= 8f) {
            donutSize
        } else {
            (safeHeight - textBottomMargin - textHeight - DONUT_TO_TEXT_GAP_DP - 8f).coerceIn(50f, donutSize)
        }

        val squirrelHeight = (safeHeight * 0.72f).coerceIn(158f, 196f)
        val squirrelWidth = (squirrelHeight * 1.19f)
            .coerceAtLeast(188f)
            .coerceAtMost(safeWidth + 32f)

        return CompactRelationshipWidgetLayout(
            hostWidthDp = safeWidth.roundDp(),
            hostHeightDp = safeHeight.roundDp(),
            squirrelWidthDp = squirrelWidth.roundDp(),
            squirrelHeightDp = squirrelHeight.roundDp(),
            squirrelStartMarginDp = -(safeWidth * 0.075f).coerceIn(16f, 28f).roundDp(),
            squirrelTopMarginDp = -(safeHeight * 0.105f).coerceIn(16f, 24f).roundDp(),
            donutSizeDp = adjustedDonutSize.roundDp(),
            donutEndMarginDp = 0f,
            donutBottomMarginDp = -DONUT_TO_TEXT_GAP_DP,
            textWidthDp = textWidth.roundDp(),
            textHeightDp = textHeight.roundDp(),
            textEndMarginDp = textEndMargin.roundDp(),
            textBottomMarginDp = textBottomMargin.roundDp()
        )
    }

    private fun Float.roundDp(): Float = (this * 10f).roundToInt() / 10f
}
