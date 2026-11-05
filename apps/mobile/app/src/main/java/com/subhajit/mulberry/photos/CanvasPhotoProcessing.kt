package com.subhajit.mulberry.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Gets a taken or picked photo ready for the canvas: upright, at most [MAX_PIXELS] on its long edge
 * (enough for a photo filling the canvas), and as the JPEG that is uploaded; and cuts its subject out
 * for the sticker style. The full-size original never leaves the device. As on iOS.
 */
object CanvasPhotoProcessing {
    const val MAX_PIXELS = 1600
    private const val JPEG_QUALITY = 80
    private const val TAG = "CanvasPhotoProcessing"

    /** Scaled down so the long edge is at most [MAX_PIXELS]; never up. */
    fun fitted(bitmap: Bitmap): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= MAX_PIXELS) return bitmap
        val scale = MAX_PIXELS.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** A camera still turned upright by [rotationDegrees], mirrored for the front camera as its preview shows it. */
    fun upright(bitmap: Bitmap, rotationDegrees: Int, mirrored: Boolean): Bitmap {
        val fittedBitmap = fitted(bitmap)
        if (rotationDegrees == 0 && !mirrored) return fittedBitmap
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (mirrored) postScale(-1f, 1f)
        }
        val turned = Bitmap.createBitmap(fittedBitmap, 0, 0, fittedBitmap.width, fittedBitmap.height, matrix, true)
        if (turned !== fittedBitmap) fittedBitmap.recycle()
        return turned
    }

    /** A photo picked from the library, decoded straight to upload size and turned upright. */
    suspend fun prepared(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            if (longEdge <= 0) return@runCatching null
            var sample = 1
            while (longEdge / (sample * 2) >= MAX_PIXELS) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val orientation = resolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            oriented(decoded, orientation)
        }.onFailure { error -> Log.w(TAG, "Couldn't read the picked photo", error) }.getOrNull()
    }

    fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        output.toByteArray()
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }

    /**
     * The photo's subject, cut out inside a white outline and cropped close around it, as iOS lifts
     * it. Null when there is no clear subject, or the cut-out model can't be had on this device.
     */
    suspend fun cutout(bitmap: Bitmap): Bitmap? {
        val foreground = segmentForeground(bitmap) ?: return null
        return withContext(Dispatchers.Default) {
            try {
                outlined(foreground)
            } finally {
                foreground.recycle()
            }
        }
    }

    private suspend fun segmentForeground(bitmap: Bitmap): Bitmap? {
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        return try {
            suspendCancellableCoroutine { continuation ->
                segmenter.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result -> continuation.resume(result.foregroundBitmap) }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Subject segmentation failed", error)
                        continuation.resume(null)
                    }
            }
        } finally {
            segmenter.close()
        }
    }

    /** The subject over its shape grown outward in white, cropped close around both. */
    private fun outlined(foreground: Bitmap): Bitmap? {
        val subject = opaqueBounds(foreground) ?: return null
        val outline = max(6f, max(subject.width(), subject.height()) * 0.028f)
        val pad = (outline * 2).roundToInt()
        val width = foreground.width + pad * 2
        val height = foreground.height + pad * 2
        val composed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)

        // The outline: the subject's shape stamped in white all around it, a ring at a time. An
        // alpha-only bitmap draws in the paint's colour.
        val mask = foreground.extractAlpha()
        val white = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        val rings = listOf(outline, outline * 0.66f, outline * 0.33f)
        for (radius in rings) {
            val steps = max(16, (2 * PI * radius / 2.5).roundToInt())
            for (step in 0 until steps) {
                val angle = 2 * PI * step / steps
                canvas.drawBitmap(
                    mask,
                    pad + (cos(angle) * radius).toFloat(),
                    pad + (sin(angle) * radius).toFloat(),
                    white
                )
            }
        }
        mask.recycle()
        canvas.drawBitmap(foreground, pad.toFloat(), pad.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))

        val margin = (outline * 1.6f).roundToInt()
        val crop = Rect(
            (subject.left + pad - margin).coerceAtLeast(0),
            (subject.top + pad - margin).coerceAtLeast(0),
            (subject.right + pad + margin).coerceAtMost(width),
            (subject.bottom + pad + margin).coerceAtMost(height)
        )
        if (crop.width() <= 0 || crop.height() <= 0) {
            composed.recycle()
            return null
        }
        val cropped = Bitmap.createBitmap(composed, crop.left, crop.top, crop.width(), crop.height())
        if (cropped !== composed) composed.recycle()
        return cropped
    }

    /** Where the bitmap is mostly opaque, or null when nothing is. */
    private fun opaqueBounds(bitmap: Bitmap): Rect? {
        val width = bitmap.width
        val height = bitmap.height
        val row = IntArray(width)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if ((row[x] ushr 24) > 127) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        // Too small a subject to be what the photo is of.
        if ((maxX - minX + 1) * (maxY - minY + 1) < min(width, height)) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun oriented(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return fitted(bitmap)
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return fitted(turned)
    }
}
