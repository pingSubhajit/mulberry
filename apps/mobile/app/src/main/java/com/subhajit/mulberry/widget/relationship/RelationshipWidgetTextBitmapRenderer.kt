package com.subhajit.mulberry.widget.relationship

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
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

    private const val PRIMARY_TEXT_SIZE_SP = 46f
    private const val SECONDARY_TEXT_SIZE_SP = 18f
    private const val CAPTION_TEXT_SIZE_SP = 11f
    private const val PRIMARY_TO_SECONDARY_GAP_DP = 12f
    private const val SECONDARY_TO_CAPTION_GAP_DP = 12f
    private const val PRIMARY_TO_CAPTION_GAP_DP = 8f

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

        drawCenteredTextCluster(
            context = context,
            canvas = canvas,
            heightPx = heightPx,
            rightEdgeX = rightEdgeX,
            primaryPaint = primaryPaint,
            secondaryPaint = secondaryPaint,
            captionPaint = captionPaint,
            primaryText = primaryText,
            secondaryText = secondaryText,
            captionText = captionText
        )

        return bitmap
    }

    private fun drawCenteredTextCluster(
        context: Context,
        canvas: Canvas,
        heightPx: Int,
        rightEdgeX: Float,
        primaryPaint: Paint,
        secondaryPaint: Paint,
        captionPaint: Paint,
        primaryText: String,
        secondaryText: String?,
        captionText: String
    ) {
        val primaryBounds = primaryPaint.textBounds(primaryText)
        val secondaryBounds = secondaryText
            ?.takeIf { it.isNotBlank() }
            ?.let { secondaryPaint.textBounds(it) }
        val captionBounds = captionPaint.textBounds(captionText)
        val primaryToSecondaryGap = dpToPx(context, PRIMARY_TO_SECONDARY_GAP_DP)
        val secondaryToCaptionGap = dpToPx(context, SECONDARY_TO_CAPTION_GAP_DP)
        val primaryToCaptionGap = dpToPx(context, PRIMARY_TO_CAPTION_GAP_DP)

        val textHeight = if (secondaryText.isNullOrBlank() || secondaryBounds == null) {
            primaryBounds.height() + primaryToCaptionGap + captionBounds.height()
        } else {
            primaryBounds.height() +
                primaryToSecondaryGap +
                secondaryBounds.height() +
                secondaryToCaptionGap +
                captionBounds.height()
        }
        val primaryTop = ((heightPx - textHeight) / 2f).coerceAtLeast(0f)
        val primaryBaseline = primaryTop - primaryBounds.top
        canvas.drawText(primaryText, rightEdgeX, primaryBaseline, primaryPaint)

        if (secondaryText.isNullOrBlank() || secondaryBounds == null) {
            val captionTop = primaryBaseline + primaryBounds.bottom + primaryToCaptionGap
            val captionBaseline = captionTop - captionBounds.top
            canvas.drawText(captionText, rightEdgeX, captionBaseline, captionPaint)
        } else {
            val secondaryTop = primaryBaseline + primaryBounds.bottom + primaryToSecondaryGap
            val secondaryBaseline = secondaryTop - secondaryBounds.top
            canvas.drawText(secondaryText, rightEdgeX, secondaryBaseline, secondaryPaint)

            val captionTop = secondaryBaseline + secondaryBounds.bottom + secondaryToCaptionGap
            val captionBaseline = captionTop - captionBounds.top
            canvas.drawText(captionText, rightEdgeX, captionBaseline, captionPaint)
        }
    }

    private fun Paint.textBounds(text: String): Rect {
        val bounds = Rect()
        getTextBounds(text, 0, text.length, bounds)
        return bounds
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
