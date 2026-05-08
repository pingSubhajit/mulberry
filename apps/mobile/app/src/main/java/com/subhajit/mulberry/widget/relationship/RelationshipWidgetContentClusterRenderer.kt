package com.subhajit.mulberry.widget.relationship

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.subhajit.mulberry.R
import kotlin.math.roundToInt

internal object RelationshipWidgetContentClusterRenderer {
    private const val MEDIUM_WIDTH_DP = 300
    private const val MEDIUM_HEIGHT_DP = 174
    private const val LARGE_WIDTH_DP = 320
    private const val LARGE_HEIGHT_DP = 186

    private const val MEDIUM_DONUT_SIZE_DP = 72
    private const val LARGE_DONUT_SIZE_DP = 79

    private const val DONUT_TO_TEXT_GAP_DP = 14f
    private const val PRIMARY_TO_SECONDARY_GAP_DP = 10f
    private const val SECONDARY_TO_CAPTION_GAP_DP = 8f
    private const val PRIMARY_TO_CAPTION_GAP_DP = 12f
    private const val TEXT_HORIZONTAL_INSET_DP = 4f

    private const val PRIMARY_TEXT_SIZE_SP = 46f
    private const val SECONDARY_TEXT_SIZE_SP = 18f
    private const val CAPTION_TEXT_SIZE_SP = 11f

    private val PRIMARY_GRADIENT_START = Color.parseColor("#D81012")
    private val PRIMARY_GRADIENT_END = Color.parseColor("#B31329")
    private val SECONDARY_COLOR = Color.BLACK

    fun render(
        context: Context,
        size: RelationshipTrackerWidgetProvider.RelationshipWidgetSize,
        primaryText: String,
        secondaryText: String?,
        captionText: String,
        progressFraction: Float,
        primaryUsesGradient: Boolean
    ): Bitmap {
        val spec = ClusterSpec.forSize(size)
        val density = context.resources.displayMetrics.density
        val widthPx = (spec.widthDp * density).roundToInt().coerceAtLeast(1)
        val heightPx = (spec.heightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.poppins_extrabold)
            textAlign = Paint.Align.RIGHT
        }
        val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
            textAlign = Paint.Align.RIGHT
            color = SECONDARY_COLOR
        }
        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.poppins_medium)
            textAlign = Paint.Align.RIGHT
            color = SECONDARY_COLOR
        }

        val horizontalInsetPx = dpToPx(context, TEXT_HORIZONTAL_INSET_DP)
        val rightEdgeX = widthPx - horizontalInsetPx
        val maxTextWidth = widthPx - (horizontalInsetPx * 2f)

        primaryPaint.textSize = fitTextSizePx(
            paint = primaryPaint,
            desiredPx = spToPx(context, PRIMARY_TEXT_SIZE_SP),
            minPx = spToPx(context, 40f),
            text = primaryText,
            maxWidthPx = maxTextWidth
        )
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

        val donutSizePx = dpToPx(context, spec.donutSizeDp.toFloat()).roundToInt()
        val primaryBounds = primaryPaint.textBounds(primaryText)
        val secondaryBounds = secondaryText
            ?.takeIf { it.isNotBlank() }
            ?.let { secondaryPaint.textBounds(it) }
        val captionBounds = captionPaint.textBounds(captionText)
        val textHeight = if (secondaryText.isNullOrBlank() || secondaryBounds == null) {
            primaryBounds.height() + dpToPx(context, PRIMARY_TO_CAPTION_GAP_DP) + captionBounds.height()
        } else {
            primaryBounds.height() +
                dpToPx(context, PRIMARY_TO_SECONDARY_GAP_DP) +
                secondaryBounds.height() +
                dpToPx(context, SECONDARY_TO_CAPTION_GAP_DP) +
                captionBounds.height()
        }
        val contentHeight = donutSizePx + dpToPx(context, DONUT_TO_TEXT_GAP_DP) + textHeight
        val contentTop = ((heightPx - contentHeight) / 2f).coerceAtLeast(0f)

        val donutBitmap = RelationshipWidgetProgressDonutRenderer.render(
            context = context,
            sizeDp = spec.donutSizeDp,
            progressFraction = progressFraction
        )
        val donutLeft = widthPx - donutSizePx
        canvas.drawBitmap(
            donutBitmap,
            null,
            RectF(donutLeft.toFloat(), contentTop, widthPx.toFloat(), contentTop + donutSizePx),
            bitmapPaint
        )

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

        val primaryTop = contentTop + donutSizePx + dpToPx(context, DONUT_TO_TEXT_GAP_DP)
        val primaryBaseline = primaryTop - primaryBounds.top
        canvas.drawText(primaryText, rightEdgeX, primaryBaseline, primaryPaint)

        if (secondaryText.isNullOrBlank() || secondaryBounds == null) {
            val captionTop = primaryBaseline + primaryBounds.bottom + dpToPx(context, PRIMARY_TO_CAPTION_GAP_DP)
            val captionBaseline = captionTop - captionBounds.top
            canvas.drawText(captionText, rightEdgeX, captionBaseline, captionPaint)
        } else {
            val secondaryTop = primaryBaseline + primaryBounds.bottom + dpToPx(context, PRIMARY_TO_SECONDARY_GAP_DP)
            val secondaryBaseline = secondaryTop - secondaryBounds.top
            canvas.drawText(secondaryText, rightEdgeX, secondaryBaseline, secondaryPaint)

            val captionTop = secondaryBaseline + secondaryBounds.bottom + dpToPx(context, SECONDARY_TO_CAPTION_GAP_DP)
            val captionBaseline = captionTop - captionBounds.top
            canvas.drawText(captionText, rightEdgeX, captionBaseline, captionPaint)
        }

        return bitmap
    }

    private data class ClusterSpec(
        val widthDp: Int,
        val heightDp: Int,
        val donutSizeDp: Int
    ) {
        companion object {
            fun forSize(size: RelationshipTrackerWidgetProvider.RelationshipWidgetSize): ClusterSpec {
                return if (size == RelationshipTrackerWidgetProvider.RelationshipWidgetSize.LARGE) {
                    ClusterSpec(widthDp = LARGE_WIDTH_DP, heightDp = LARGE_HEIGHT_DP, donutSizeDp = LARGE_DONUT_SIZE_DP)
                } else {
                    ClusterSpec(widthDp = MEDIUM_WIDTH_DP, heightDp = MEDIUM_HEIGHT_DP, donutSizeDp = MEDIUM_DONUT_SIZE_DP)
                }
            }
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

    private fun dpToPx(context: Context, dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    private fun spToPx(context: Context, sp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            context.resources.displayMetrics
        )
    }
}
