package com.subhajit.mulberry.widget.relationship

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.subhajit.mulberry.R
import kotlin.math.roundToInt

internal object RelationshipWidgetTextBitmapRenderer {
    private const val MEDIUM_TEXT_BLOCK_WIDTH_DP = 300
    private const val MEDIUM_TEXT_BLOCK_HEIGHT_DP = 112
    private const val MEDIUM_COMPACT_TEXT_BLOCK_HEIGHT_DP = 84
    private const val LARGE_TEXT_BLOCK_WIDTH_DP = 320
    private const val LARGE_TEXT_BLOCK_HEIGHT_DP = 131

    private const val MEDIUM_PRIMARY_TOP_DP = 0f
    private const val MEDIUM_SECONDARY_TOP_DP = 60f
    private const val MEDIUM_CAPTION_TOP_DP = 88f

    private const val LARGE_PRIMARY_TOP_DP = 0f
    private const val LARGE_SECONDARY_TOP_DP = 63f
    private const val LARGE_CAPTION_TOP_DP = 100f

    private const val PRIMARY_TEXT_SIZE_SP = 46f
    private const val SECONDARY_TEXT_SIZE_SP = 18f
    private const val CAPTION_TEXT_SIZE_SP = 11f

    // Figma gradient for the years text.
    private val PRIMARY_GRADIENT_START = Color.parseColor("#D81012")
    private val PRIMARY_GRADIENT_END = Color.parseColor("#B31329")

    private val SECONDARY_COLOR = Color.BLACK

    fun render(
        context: Context,
        size: RelationshipTrackerWidgetProvider.RelationshipWidgetSize,
        primaryText: String,
        secondaryText: String?,
        captionText: String,
        primaryUsesGradient: Boolean
    ): Bitmap {
        return when (size) {
            RelationshipTrackerWidgetProvider.RelationshipWidgetSize.SQUARE ->
                renderSmall(
                    context = context,
                    primaryText = primaryText,
                    secondaryText = secondaryText ?: captionText,
                    primaryUsesGradient = primaryUsesGradient
                )
            RelationshipTrackerWidgetProvider.RelationshipWidgetSize.MEDIUM ->
                renderMediumLike(
                    context = context,
                    textBlockWidthDp = MEDIUM_TEXT_BLOCK_WIDTH_DP,
                    textBlockHeightDp = if (secondaryText.isNullOrBlank()) {
                        MEDIUM_COMPACT_TEXT_BLOCK_HEIGHT_DP
                    } else {
                        MEDIUM_TEXT_BLOCK_HEIGHT_DP
                    },
                    primaryTopDp = MEDIUM_PRIMARY_TOP_DP,
                    secondaryTopDp = MEDIUM_SECONDARY_TOP_DP,
                    captionTopDp = MEDIUM_CAPTION_TOP_DP,
                    primaryText = primaryText,
                    secondaryText = secondaryText,
                    captionText = captionText,
                    primaryUsesGradient = primaryUsesGradient
                )
            RelationshipTrackerWidgetProvider.RelationshipWidgetSize.LARGE ->
                renderMediumLike(
                    context = context,
                    textBlockWidthDp = LARGE_TEXT_BLOCK_WIDTH_DP,
                    textBlockHeightDp = LARGE_TEXT_BLOCK_HEIGHT_DP,
                    primaryTopDp = LARGE_PRIMARY_TOP_DP,
                    secondaryTopDp = LARGE_SECONDARY_TOP_DP,
                    captionTopDp = LARGE_CAPTION_TOP_DP,
                    primaryText = primaryText,
                    secondaryText = secondaryText,
                    captionText = captionText,
                    primaryUsesGradient = primaryUsesGradient
                )
        }
    }

    private fun renderSmall(
        context: Context,
        primaryText: String,
        secondaryText: String,
        primaryUsesGradient: Boolean
    ): Bitmap {
        val widthPx = dpToPx(context, 168)
        val heightPx = dpToPx(context, 78)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val primaryTypeface = ResourcesCompat.getFont(context, R.font.poppins_extrabold)
        val secondaryTypeface = ResourcesCompat.getFont(context, R.font.poppins_bold)

        val horizontalInsetPx = dpToPx(context, 4).toFloat()
        val rightEdgeX = widthPx - horizontalInsetPx
        val maxTextWidth = widthPx - (horizontalInsetPx * 2f)

        val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = primaryTypeface
            textAlign = Paint.Align.RIGHT
        }

        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = secondaryTypeface
            textAlign = Paint.Align.RIGHT
            color = SECONDARY_COLOR
        }

        val primarySize = fitTextSizePx(
            paint = primaryPaint,
            desiredPx = spToPx(context, 47f),
            minPx = spToPx(context, 32f),
            text = primaryText,
            maxWidthPx = maxTextWidth
        )
        primaryPaint.textSize = primarySize
        if (primaryUsesGradient) {
            val textWidth = primaryPaint.measureText(primaryText).coerceAtLeast(1f)
            primaryPaint.shader = LinearGradient(
                rightEdgeX - textWidth,
                0f,
                rightEdgeX,
                0f,
                PRIMARY_GRADIENT_START,
                PRIMARY_GRADIENT_END,
                Shader.TileMode.CLAMP
            )
        } else {
            primaryPaint.shader = null
            primaryPaint.color = PRIMARY_GRADIENT_START
        }

        secondaryPaint.textSize = fitTextSizePx(
            paint = secondaryPaint,
            desiredPx = spToPx(context, 19f),
            minPx = spToPx(context, 14f),
            text = secondaryText,
            maxWidthPx = maxTextWidth
        )

        canvas.drawText(primaryText, rightEdgeX, dpToPx(context, 51f), primaryPaint)
        canvas.drawText(secondaryText, rightEdgeX, dpToPx(context, 75f), secondaryPaint)

        return bitmap
    }

    private fun renderMediumLike(
        context: Context,
        textBlockWidthDp: Int,
        textBlockHeightDp: Int,
        primaryTopDp: Float,
        secondaryTopDp: Float,
        captionTopDp: Float,
        primaryText: String,
        secondaryText: String?,
        captionText: String,
        primaryUsesGradient: Boolean
    ): Bitmap {
        val widthPx = dpToPx(context, textBlockWidthDp)
        val heightPx = dpToPx(context, textBlockHeightDp)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val primaryTypeface = ResourcesCompat.getFont(context, R.font.poppins_extrabold)
        val secondaryTypeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
        val captionTypeface = ResourcesCompat.getFont(context, R.font.poppins_medium)

        val horizontalInsetPx = dpToPx(context, 4).toFloat()
        val rightEdgeX = widthPx - horizontalInsetPx
        val maxTextWidth = widthPx - (horizontalInsetPx * 2f)

        val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = primaryTypeface
            textAlign = Paint.Align.RIGHT
        }

        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = secondaryTypeface
            textAlign = Paint.Align.RIGHT
            color = SECONDARY_COLOR
        }

        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = captionTypeface
            textAlign = Paint.Align.RIGHT
            color = SECONDARY_COLOR
        }

        val primarySize = fitTextSizePx(
            paint = primaryPaint,
            desiredPx = spToPx(context, PRIMARY_TEXT_SIZE_SP),
            minPx = spToPx(context, 40f),
            text = primaryText,
            maxWidthPx = maxTextWidth
        )

        primaryPaint.textSize = primarySize
        if (primaryUsesGradient) {
            val textWidth = primaryPaint.measureText(primaryText).coerceAtLeast(1f)
            primaryPaint.shader = LinearGradient(
                rightEdgeX - textWidth,
                0f,
                rightEdgeX,
                0f,
                PRIMARY_GRADIENT_START,
                PRIMARY_GRADIENT_END,
                Shader.TileMode.CLAMP
            )
        } else {
            primaryPaint.shader = null
            primaryPaint.color = PRIMARY_GRADIENT_START
        }

        secondaryPaint.textSize = fitTextSizePx(
            paint = secondaryPaint,
            desiredPx = spToPx(context, SECONDARY_TEXT_SIZE_SP),
            minPx = spToPx(context, 14f),
            text = secondaryText.orEmpty(),
            maxWidthPx = maxTextWidth
        )

        captionPaint.textSize = fitTextSizePx(
            paint = captionPaint,
            desiredPx = spToPx(context, CAPTION_TEXT_SIZE_SP),
            minPx = spToPx(context, 9f),
            text = captionText,
            maxWidthPx = maxTextWidth
        )

        val primaryTop = dpToPx(context, primaryTopDp)
        val primaryBaseline = topToBaselineY(primaryPaint, primaryTop)
        canvas.drawText(primaryText, rightEdgeX, primaryBaseline, primaryPaint)

        if (!secondaryText.isNullOrBlank()) {
            val secondaryTop = dpToPx(context, secondaryTopDp)
            val secondaryBaseline = topToBaselineY(secondaryPaint, secondaryTop)
            canvas.drawText(secondaryText, rightEdgeX, secondaryBaseline, secondaryPaint)

            val captionTop = dpToPx(context, captionTopDp)
            val captionBaseline = topToBaselineY(captionPaint, captionTop)
            canvas.drawText(captionText, rightEdgeX, captionBaseline, captionPaint)
        } else {
            val primaryBottom = visualTextBottom(primaryPaint, primaryText, primaryBaseline)
            val captionTop = primaryBottom + dpToPx(context, 8).toFloat()
            val captionBaseline = topToBaselineY(captionPaint, captionTop)
            canvas.drawText(captionText, rightEdgeX, captionBaseline, captionPaint)
        }

        return bitmap
    }

    private fun topToBaselineY(paint: Paint, topY: Float): Float =
        topY - paint.fontMetrics.ascent

    private fun visualTextBottom(paint: Paint, text: String, baselineY: Float): Float {
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        return baselineY + bounds.bottom
    }

    private fun fitTextSizePx(
        paint: Paint,
        desiredPx: Float,
        minPx: Float,
        text: String,
        maxWidthPx: Float
    ): Float {
        if (text.isBlank()) return desiredPx
        val safeDesired = desiredPx.coerceAtLeast(1f)
        paint.textSize = safeDesired
        val width = paint.measureText(text)
        if (width <= maxWidthPx || maxWidthPx <= 0f) return safeDesired
        val scaled = safeDesired * (maxWidthPx / width)
        val safeMin = minPx.coerceAtLeast(1f)
        if (scaled <= safeMin) return scaled.coerceAtLeast(1f)
        paint.textSize = safeMin
        return if (paint.measureText(text) <= maxWidthPx) safeMin else scaled.coerceAtLeast(1f)
    }

    fun dpToPx(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).roundToInt()
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        val density = context.resources.displayMetrics.density
        return dp * density
    }

    private fun spToPx(context: Context, sp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            context.resources.displayMetrics
        )
    }
}
