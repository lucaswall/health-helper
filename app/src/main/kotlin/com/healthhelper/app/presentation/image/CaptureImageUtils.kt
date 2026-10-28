package com.healthhelper.app.presentation.image

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Maximum size of a captured or shared image accepted by the capture flows. */
const val MAX_IMAGE_BYTES: Int = 20 * 1024 * 1024 // 20MB

/** Long-edge size the image is scaled to before being sent to the Anthropic API. */
const val API_MAX_IMAGE_DIMENSION: Int = 1568

/** Cache sub-directory MainActivity copies share-intent images into. */
const val SHARED_IMAGES_DIR: String = "shared_images"

/** Cache sub-directories that hold transient capture images. */
val CAPTURE_IMAGE_CACHE_DIRS: List<String> = listOf(SHARED_IMAGES_DIR, "bp_images", "glucose_images")

/** Capture images older than this are considered abandoned and swept at startup. */
const val STALE_CAPTURE_FILE_AGE_MS: Long = 60 * 60 * 1000L // 1 hour

class ImageTooLargeException(maxSize: Int) :
    IllegalArgumentException("Image exceeds maximum size of ${maxSize / (1024 * 1024)}MB")

/**
 * Copies [input] to [output], throwing [ImageTooLargeException] once more than [maxSize] bytes
 * have been read. Returns the number of bytes copied.
 */
fun copyLimited(input: InputStream, output: OutputStream, maxSize: Int = MAX_IMAGE_BYTES): Long {
    val buffer = ByteArray(8192)
    var totalRead = 0L
    while (true) {
        val bytesRead = input.read(buffer)
        if (bytesRead == -1) break
        totalRead += bytesRead
        if (totalRead > maxSize) {
            throw ImageTooLargeException(maxSize)
        }
        output.write(buffer, 0, bytesRead)
    }
    return totalRead
}

/** Reads all of [inputStream], throwing [ImageTooLargeException] when it exceeds [maxSize]. */
fun readBytesLimited(inputStream: InputStream, maxSize: Int = MAX_IMAGE_BYTES): ByteArray {
    val output = ByteArrayOutputStream()
    copyLimited(inputStream, output, maxSize)
    return output.toByteArray()
}

/**
 * Largest power-of-two sample size that still leaves the long edge of the decoded image
 * at or above [maxDimension], so the final scale step only ever downsizes.
 */
fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
    val longEdge = maxOf(width, height)
    var sample = 1
    while (longEdge / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    return sample
}

/** Rotation (degrees, clockwise) plus optional horizontal mirror needed to display an image upright. */
data class ExifTransform(
    val rotationDegrees: Float,
    val flipHorizontal: Boolean,
) {
    val isIdentity: Boolean get() = rotationDegrees == 0f && !flipHorizontal
}

// EXIF orientation tag values (TIFF/EXIF spec), mirrored from ExifInterface.ORIENTATION_*.
private const val ORIENTATION_FLIP_HORIZONTAL = 2
private const val ORIENTATION_ROTATE_180 = 3
private const val ORIENTATION_FLIP_VERTICAL = 4
private const val ORIENTATION_TRANSPOSE = 5
private const val ORIENTATION_ROTATE_90 = 6
private const val ORIENTATION_TRANSVERSE = 7
private const val ORIENTATION_ROTATE_270 = 8

/**
 * Maps an EXIF orientation value to the transform that makes the pixels upright.
 * Rotation is applied first, then the horizontal flip (same order as Glide's TransformationUtils).
 */
fun exifTransformFor(orientation: Int): ExifTransform = when (orientation) {
    ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0f, true)
    ORIENTATION_ROTATE_180 -> ExifTransform(180f, false)
    ORIENTATION_FLIP_VERTICAL -> ExifTransform(180f, true)
    ORIENTATION_TRANSPOSE -> ExifTransform(90f, true)
    ORIENTATION_ROTATE_90 -> ExifTransform(90f, false)
    ORIENTATION_TRANSVERSE -> ExifTransform(-90f, true)
    ORIENTATION_ROTATE_270 -> ExifTransform(-90f, false)
    else -> ExifTransform(0f, false)
}

/** True when [file] resolves (following `..` and symlinks) to a path strictly inside [dir]. */
fun isInsideDirectory(file: File, dir: File): Boolean {
    val dirPath = dir.canonicalPath + File.separator
    return file.canonicalPath.startsWith(dirPath)
}

/**
 * Only `content://` URIs from other apps are accepted for shared images. `file://` URIs and the
 * app's own FileProvider authority would let a caller make us read our own private files.
 */
fun isAllowedSharedImageUri(scheme: String?, authority: String?, packageName: String): Boolean =
    scheme == "content" &&
        !authority.isNullOrEmpty() &&
        authority != "$packageName.fileprovider"

/**
 * Deletes files older than [maxAgeMs] in [dirs]. Young files are kept so an in-flight capture is
 * never raced. Returns the number of files deleted.
 */
fun sweepStaleFiles(dirs: List<File>, nowMs: Long, maxAgeMs: Long = STALE_CAPTURE_FILE_AGE_MS): Int {
    var deleted = 0
    dirs.forEach { dir ->
        dir.listFiles()?.forEach { file ->
            if (file.isFile && nowMs - file.lastModified() > maxAgeMs && file.delete()) {
                deleted++
            }
        }
    }
    return deleted
}
