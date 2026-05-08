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

internal object RelationshipWidgetCompactClusterRenderer {
    private const val PRIMARY_TEXT_SIZE_SP = 35f
    private const val SECONDARY_TEXT_SIZE_SP = 15.5f
    private const val PRIMARY_MIN_TEXT_SIZE_SP = 26f
    private const val SECONDARY_MIN_TEXT_SIZE_SP = 11.5f
    private const val TEXT_HORIZONTAL_INSET_DP = 4f
    private const val TITLE_TO_SUBTITLE_GAP_DP = 0f

    private val PRIMARY_GRADIENT_START = Color.parseColor("#D81012")
    private val PRIMARY_GRADIENT_END = Color.parseColor("#B31329")
    private val SECONDARY_COLOR = Color.BLACK

    fun render(
        context: Context,
        layout: CompactRelationshipWidgetLayout,
        primaryText: String,
        secondaryText: String?,
        captionText: String,
        progressFraction: Float,
        primaryUsesGradient: Boolean
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (layout.clusterWidthDp * density).roundToInt().coerceAtLeast(1)
        val heightPx = (layout.clusterHeightDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        val donutBitmap = RelationshipWidgetProgressDonutRenderer.render(
            context = context,
            sizeDp = layout.donutSizeDp.roundToInt(),
            progressFraction = progressFraction
        )
        val donutSizePx = (layout.donutSizeDp * density).roundToInt().coerceAtLeast(1)
        val donutLeft = widthPx - donutSizePx
        canvas.drawBitmap(
            donutBitmap,
            null,
            RectF(donutLeft.toFloat(), 0f, widthPx.toFloat(), donutSizePx.toFloat()),
            paint
        )

        val donutToTextGapPx = dpToPx(context, layout.donutToTextGapDp)
        drawTextCluster(
            context = context,
            canvas = canvas,
            widthPx = widthPx,
            topPx = donutSizePx + donutToTextGapPx,
            maxHeightPx = heightPx - donutSizePx - donutToTextGapPx,
            primaryText = primaryText,
            secondaryText = secondaryText ?: captionText,
            primaryUsesGradient = primaryUsesGradient
        )

        return bitmap
    }

    private fun drawTextCluster(
        context: Context,
        canvas: Canvas,
        widthPx: Int,
        topPx: Int,
        maxHeightPx: Int,
        primaryText: String,
        secondaryText: String,
        primaryUsesGradient: Boolean
    ) {
        val primaryTypeface = ResourcesCompat.getFont(context, R.font.poppins_extrabold)
        val secondaryTypeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
        val horizontalInsetPx = dpToPx(context, TEXT_HORIZONTAL_INSET_DP).toFloat()
        val rightEdgeX = widthPx - horizontalInsetPx
        val maxTextWidth = widthPx - (horizontalInsetPx * 2f)
        val titleToSubtitleGapPx = dpToPx(context, TITLE_TO_SUBTITLE_GAP_DP).toFloat()

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
            desiredPx = spToPx(context, PRIMARY_TEXT_SIZE_SP),
            minPx = spToPx(context, PRIMARY_MIN_TEXT_SIZE_SP),
            text = primaryText,
            maxWidthPx = maxTextWidth
        )
        primaryPaint.textSize = primarySize

        val secondarySize = fitTextSizePx(
            paint = secondaryPaint,
            desiredPx = spToPx(context, SECONDARY_TEXT_SIZE_SP),
            minPx = spToPx(context, SECONDARY_MIN_TEXT_SIZE_SP),
            text = secondaryText,
            maxWidthPx = maxTextWidth
        )
        secondaryPaint.textSize = secondarySize

        val primaryBounds = primaryPaint.textBounds(primaryText)
        val secondaryBounds = secondaryPaint.textBounds(secondaryText)
        val requiredHeight = primaryBounds.height() + titleToSubtitleGapPx + secondaryBounds.height()
        val extraVerticalSpace = maxHeightPx - requiredHeight
        val primaryVisualTop = topPx + extraVerticalSpace
        val primaryBaseline = primaryVisualTop - primaryBounds.top

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

        canvas.drawText(primaryText, rightEdgeX, primaryBaseline, primaryPaint)

        val primaryVisualBottom = primaryBaseline + primaryBounds.bottom
        val secondaryVisualTop = primaryVisualBottom + titleToSubtitleGapPx
        val secondaryBaseline = secondaryVisualTop - secondaryBounds.top
        canvas.drawText(secondaryText, rightEdgeX, secondaryBaseline, secondaryPaint)
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

    private fun dpToPx(context: Context, dp: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).roundToInt()
    }

    private fun spToPx(context: Context, sp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            context.resources.displayMetrics
        )
    }
}
