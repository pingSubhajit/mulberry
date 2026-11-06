package com.subhajit.mulberry.share

import kotlin.math.cos
import kotlin.math.sin

/** The size of the content image: the lock screen as Mulberry composes it. */
const val SHARE_CONTENT_WIDTH = 1179
const val SHARE_CONTENT_HEIGHT = 2556

/** The exported image. */
const val SHARE_OUTPUT_WIDTH = 1080
const val SHARE_OUTPUT_HEIGHT = 1920

/**
 * A 2D affine transform, `x' = a·x + c·y + tx`, `y' = b·x + d·y + ty`, in pixel coordinates with
 * y pointing down. Kept apart from android.graphics.Matrix so its maths runs in JVM tests.
 */
data class ShareAffine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val tx: Double,
    val ty: Double
) {
    fun map(x: Double, y: Double): SharePoint = SharePoint(a * x + c * y + tx, b * x + d * y + ty)

    /** Row-major 3×3 values, as android.graphics.Matrix.setValues takes them. */
    fun toMatrixValues(): FloatArray = floatArrayOf(
        a.toFloat(), c.toFloat(), tx.toFloat(),
        b.toFloat(), d.toFloat(), ty.toFloat(),
        0f, 0f, 1f
    )
}

object ShareGeometry {
    /**
     * The affine map that puts a [width]×[height] image into [quad]: topLeft ← (0,0),
     * topRight ← (width,0), bottomLeft ← (0,height). The quad is a parallelogram, so bottomRight
     * follows.
     */
    fun contentToQuad(quad: ShareQuad, width: Double, height: Double): ShareAffine {
        val origin = quad.topLeft
        return ShareAffine(
            a = (quad.topRight.x - origin.x) / width,
            b = (quad.topRight.y - origin.y) / width,
            c = (quad.bottomLeft.x - origin.x) / height,
            d = (quad.bottomLeft.y - origin.y) / height,
            tx = origin.x,
            ty = origin.y
        )
    }

    /**
     * The source and destination points for android.graphics.Matrix.setPolyToPoly with three
     * points, which gives the same affine map as [contentToQuad].
     */
    fun polyToPolyPoints(quad: ShareQuad, width: Float, height: Float): Pair<FloatArray, FloatArray> {
        val source = floatArrayOf(0f, 0f, width, 0f, 0f, height)
        val destination = floatArrayOf(
            quad.topLeft.x.toFloat(), quad.topLeft.y.toFloat(),
            quad.topRight.x.toFloat(), quad.topRight.y.toFloat(),
            quad.bottomLeft.x.toFloat(), quad.bottomLeft.y.toFloat()
        )
        return source to destination
    }

    /** The anchor point inside an unrotated [width]×[height] block. */
    fun anchorPoint(anchor: ShareBlockAnchor, width: Double, height: Double): SharePoint = when (anchor) {
        ShareBlockAnchor.TOP_LEFT -> SharePoint(0.0, 0.0)
        ShareBlockAnchor.TOP_RIGHT -> SharePoint(width, 0.0)
        ShareBlockAnchor.BOTTOM_LEFT -> SharePoint(0.0, height)
        ShareBlockAnchor.BOTTOM_RIGHT -> SharePoint(width, height)
        ShareBlockAnchor.CENTER -> SharePoint(width / 2.0, height / 2.0)
    }

    /**
     * Places a block: its [anchor] point sits at [position], and it turns about that point by
     * [rotationDegrees], clockwise for positive values (y points down).
     */
    fun blockPlacement(
        position: SharePoint,
        anchor: ShareBlockAnchor,
        rotationDegrees: Double,
        width: Double,
        height: Double
    ): ShareAffine {
        val pivot = anchorPoint(anchor, width, height)
        val radians = Math.toRadians(rotationDegrees)
        val cosine = cos(radians)
        val sine = sin(radians)
        return ShareAffine(
            a = cosine,
            b = sine,
            c = -sine,
            d = cosine,
            tx = position.x - cosine * pivot.x + sine * pivot.y,
            ty = position.y - sine * pivot.x - cosine * pivot.y
        )
    }

    /** The block's corners in output pixels: top left, top right, bottom right, bottom left. */
    fun blockCorners(placement: ShareAffine, width: Double, height: Double): List<SharePoint> = listOf(
        placement.map(0.0, 0.0),
        placement.map(width, 0.0),
        placement.map(width, height),
        placement.map(0.0, height)
    )

    /** The source rectangle that fills [targetWidth]×[targetHeight] from an image, centre-cropped. */
    fun centerCropSource(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): ShareRect {
        val scale = maxOf(targetWidth.toDouble() / sourceWidth, targetHeight.toDouble() / sourceHeight)
        val cropWidth = targetWidth / scale
        val cropHeight = targetHeight / scale
        val left = (sourceWidth - cropWidth) / 2.0
        val top = (sourceHeight - cropHeight) / 2.0
        return ShareRect(left, top, left + cropWidth, top + cropHeight)
    }
}

data class ShareRect(val left: Double, val top: Double, val right: Double, val bottom: Double)
