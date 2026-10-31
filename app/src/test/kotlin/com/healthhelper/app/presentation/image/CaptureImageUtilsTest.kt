package com.healthhelper.app.presentation.image

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureImageUtilsTest {

    @Test
    fun `computeInSampleSize keeps small images at full resolution`() {
        assertEquals(1, computeInSampleSize(1000, 800, 1568))
        assertEquals(1, computeInSampleSize(1568, 1000, 1568))
        assertEquals(1, computeInSampleSize(3000, 2000, 1568))
    }

    @Test
    fun `computeInSampleSize picks largest power of two keeping long edge at or above target`() {
        // 50MP: 8160x6120 -> /4 = 2040 >= 1568, /8 = 1020 < 1568
        assertEquals(4, computeInSampleSize(8160, 6120, 1568))
        // 12MP: 4032x3024 -> /2 = 2016 >= 1568, /4 = 1008 < 1568
        assertEquals(2, computeInSampleSize(4032, 3024, 1568))
        // 200MP portrait: 12240x16320 -> /8 = 2040, /16 = 1020
        assertEquals(8, computeInSampleSize(12240, 16320, 1568))
    }

    @Test
    fun `computeInSampleSize handles invalid dimensions`() {
        assertEquals(1, computeInSampleSize(0, 0, 1568))
        assertEquals(1, computeInSampleSize(-1, 100, 1568))
    }

    @Test
    fun `visualTokens counts started 28px patches`() {
        assertEquals(64, visualTokens(200, 200))
        assertEquals(1296, visualTokens(1000, 1000))
        assertEquals(2691, visualTokens(1920, 1080))
        assertEquals(1, visualTokens(1, 1))
    }

    @Test
    fun `fitScale keeps images that already fit at full size`() {
        assertEquals(1f, fitScale(1000, 1000))
        assertEquals(1f, fitScale(1920, 1080))
    }

    @Test
    fun `fitScale fits phone photos within both the long edge and the visual-token limit`() {
        // 4:3 and 16:9 camera photos, portrait and landscape, including 50 MP sensors.
        listOf(4032 to 3024, 3024 to 4032, 4000 to 2250, 8160 to 6120, 3840 to 2160).forEach { (w, h) ->
            val scale = fitScale(w, h)
            val sw = scaledDimension(w, scale)
            val sh = scaledDimension(h, scale)
            assertTrue(maxOf(sw, sh) <= API_MAX_IMAGE_DIMENSION, "$w x $h -> $sw x $sh long edge")
            assertTrue(visualTokens(sw, sh) <= API_MAX_VISUAL_TOKENS, "$w x $h -> $sw x $sh tokens")
            // Close to the limit, not needlessly small.
            assertTrue(visualTokens(sw, sh) > API_MAX_VISUAL_TOKENS * 0.95, "$w x $h -> $sw x $sh too small")
        }
    }

    @Test
    fun `fitScale caps a wide image by its long edge`() {
        // 4K 16:9 hits the 2576 px long edge before the token limit, as in the API's own table.
        val scale = fitScale(3840, 2160)
        assertEquals(2576, scaledDimension(3840, scale))
        assertEquals(1449, scaledDimension(2160, scale))
    }

    @Test
    fun `fitScale handles invalid dimensions`() {
        assertEquals(1f, fitScale(0, 0))
        assertEquals(1f, fitScale(-1, 100))
    }

    @Test
    fun `exifTransformFor maps all EXIF orientations`() {
        assertTrue(exifTransformFor(1).isIdentity)
        assertTrue(exifTransformFor(0).isIdentity)
        assertEquals(ExifTransform(0f, true), exifTransformFor(2))
        assertEquals(ExifTransform(180f, false), exifTransformFor(3))
        assertEquals(ExifTransform(180f, true), exifTransformFor(4))
        assertEquals(ExifTransform(90f, true), exifTransformFor(5))
        assertEquals(ExifTransform(90f, false), exifTransformFor(6))
        assertEquals(ExifTransform(-90f, true), exifTransformFor(7))
        assertEquals(ExifTransform(-90f, false), exifTransformFor(8))
    }

    @Test
    fun `copyLimited copies data within the limit`() {
        val data = ByteArray(20_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val copied = copyLimited(ByteArrayInputStream(data), out, maxSize = 20_000)
        assertEquals(20_000L, copied)
        assertContentEquals(data, out.toByteArray())
    }

    @Test
    fun `copyLimited throws once the limit is exceeded`() {
        val data = ByteArray(20_001)
        assertFailsWith<ImageTooLargeException> {
            copyLimited(ByteArrayInputStream(data), ByteArrayOutputStream(), maxSize = 20_000)
        }
    }

    @Test
    fun `readBytesLimited throws ImageTooLargeException over the limit`() {
        assertFailsWith<ImageTooLargeException> {
            readBytesLimited(ByteArrayInputStream(ByteArray(11)), maxSize = 10)
        }
        assertEquals(10, readBytesLimited(ByteArrayInputStream(ByteArray(10)), maxSize = 10).size)
    }

    @Test
    fun `isAllowedSharedImageUri accepts content URIs from other apps`() {
        assertTrue(isAllowedSharedImageUri("content", "com.google.android.apps.photos.contentprovider", "com.healthhelper.app"))
    }

    @Test
    fun `isAllowedSharedImageUri rejects file URIs and own FileProvider`() {
        assertFalse(isAllowedSharedImageUri("file", null, "com.healthhelper.app"))
        assertFalse(isAllowedSharedImageUri("file", "", "com.healthhelper.app"))
        assertFalse(isAllowedSharedImageUri("content", "com.healthhelper.app.fileprovider", "com.healthhelper.app"))
        assertFalse(isAllowedSharedImageUri("content", null, "com.healthhelper.app"))
        assertFalse(isAllowedSharedImageUri(null, "x", "com.healthhelper.app"))
    }

    @Test
    fun `sweepStaleFiles deletes only files older than max age`(@TempDir tmp: File) {
        val dir = File(tmp, "bp_images").apply { mkdirs() }
        val now = 10_000_000L
        val old = File(dir, "old.jpg").apply { writeText("x"); setLastModified(now - 2 * STALE_CAPTURE_FILE_AGE_MS) }
        val fresh = File(dir, "fresh.jpg").apply { writeText("x"); setLastModified(now - 1_000L) }
        val missingDir = File(tmp, "glucose_images")

        val deleted = sweepStaleFiles(listOf(dir, missingDir), now)

        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `isInsideDirectory accepts files under the directory only`() {
        val dir = java.io.File("/cache/shared_images")
        kotlin.test.assertTrue(isInsideDirectory(java.io.File("/cache/shared_images/a.jpg"), dir))
        kotlin.test.assertFalse(isInsideDirectory(java.io.File("/cache/shared_images/../secret.db"), dir))
        kotlin.test.assertFalse(isInsideDirectory(java.io.File("/cache/shared_images_evil/a.jpg"), dir))
        kotlin.test.assertFalse(isInsideDirectory(dir, dir))
    }
}
