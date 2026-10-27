package com.healthhelper.app.presentation.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Prepares a captured or shared photo for the Anthropic API: decodes with an inSampleSize so large
 * photos never hit full-resolution allocation, applies the EXIF orientation, scales the long edge
 * down to [API_MAX_IMAGE_DIMENSION] and re-encodes as JPEG. Returns null when the image cannot be
 * processed (including when decoding runs out of memory).
 */
class ImagePreparer @Inject constructor() {

    fun prepare(imageBytes: ByteArray, maxDimension: Int = API_MAX_IMAGE_DIMENSION): ByteArray? {
        var decoded: Bitmap? = null
        var transformed: Bitmap? = null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Timber.w("ImagePreparer: could not decode image dimensions")
                return null
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
            if (bitmap == null) {
                Timber.w("ImagePreparer: BitmapFactory.decodeByteArray returned null")
                return null
            }
            decoded = bitmap

            val longEdge = maxOf(bitmap.width, bitmap.height)
            val scale = if (longEdge > maxDimension) maxDimension.toFloat() / longEdge else 1f
            val exif = exifTransformFor(readExifOrientation(imageBytes))

            val result = if (scale == 1f && exif.isIdentity) {
                bitmap
            } else {
                val matrix = Matrix().apply {
                    setRotate(exif.rotationDegrees)
                    if (exif.flipHorizontal) postScale(-1f, 1f)
                    if (scale != 1f) postScale(scale, scale)
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            transformed = result

            val outputStream = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            outputStream.toByteArray()
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "ImagePreparer: out of memory while processing image")
            null
        } catch (e: Exception) {
            Timber.w(e, "ImagePreparer: failed to process image")
            null
        } finally {
            if (transformed != null && transformed !== decoded) transformed.recycle()
            decoded?.recycle()
        }
    }

    private fun readExifOrientation(imageBytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(imageBytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        Timber.d(e, "ImagePreparer: could not read EXIF orientation")
        ExifInterface.ORIENTATION_NORMAL
    }
}
