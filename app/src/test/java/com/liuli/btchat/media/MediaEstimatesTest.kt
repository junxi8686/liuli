package com.liuli.btchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.liuli.btchat.core.ImportedMedia
import java.io.File

/**
 * Pure-JVM tests for the size policy and the ETA maths — the two places where a wrong number is
 * visible to the user instead of failing loudly.
 */
class MediaEstimatesTest {

    private val kb = 1024L
    private val mb = 1024L * 1024L

    // ------------------------------------------------------ transferSeconds

    @Test
    fun `empty payload takes no time`() {
        assertEquals(0, transferSeconds(0L))
        assertEquals(0, MediaEstimates.transferSeconds(-42L))
    }

    @Test
    fun `bytes round up to whole seconds`() {
        assertEquals(1, transferSeconds(1L))
        assertEquals(1, transferSeconds(150L * kb))
        assertEquals(2, transferSeconds(150L * kb + 1L))
    }

    @Test
    fun `known sizes estimate at 150 KB per second`() {
        // 30 MB / 150 KB/s = 204.8 s
        assertEquals(205, transferSeconds(30L * mb))
        // 50 MB / 150 KB/s = 341.3 s
        assertEquals(342, transferSeconds(50L * mb))
    }

    @Test
    fun `huge values saturate instead of overflowing`() {
        assertEquals(Int.MAX_VALUE, transferSeconds(Long.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, transferSeconds(Long.MAX_VALUE - 1L))
    }

    @Test
    fun `a measured rate replaces the nominal one`() {
        // 1 MB: 7 s at the nominal 150 KB/s, 32 s at a measured 32 KB/s.
        assertEquals(7, MediaEstimates.transferSeconds(1L * mb))
        assertEquals(32, MediaEstimates.transferSeconds(1L * mb, 32L * 1024L))

        // An unknown or nonsensical measurement falls back to the nominal rate, never to zero.
        assertEquals(7, MediaEstimates.transferSeconds(1L * mb, 0L))
        assertEquals(7, MediaEstimates.transferSeconds(1L * mb, -5L))
        assertEquals(0, MediaEstimates.transferSeconds(0L, 4_000L))
    }

    // --------------------------------------------------------- humanDuration

    @Test
    fun `duration text switches unit at the right boundary`() {
        assertEquals("不到 1 秒", MediaEstimates.humanDuration(0))
        assertEquals("约 30 秒", MediaEstimates.humanDuration(30))
        assertEquals("约 59 秒", MediaEstimates.humanDuration(59))
        assertEquals("约 1 分钟", MediaEstimates.humanDuration(60))
        assertEquals("约 2 分钟", MediaEstimates.humanDuration(90))
        assertEquals("约 4 分钟", MediaEstimates.humanDuration(240))
        assertEquals("约 1 小时", MediaEstimates.humanDuration(3600))
        assertEquals("约 1 小时 1 分钟", MediaEstimates.humanDuration(3660))
        assertEquals("约 1 小时 30 分钟", MediaEstimates.humanDuration(5400))
    }

    // ----------------------------------------------------------- MediaLimits

    @Test
    fun `limits match the product decision`() {
        assertEquals(50L * mb, MediaLimits.MAX_SEND_BYTES)
        assertEquals(15L * mb, MediaLimits.WARN_SEND_BYTES)
    }

    @Test
    fun `nothing to say about empty or missing sizes`() {
        assertNull(MediaLimits.check(0L))
        assertNull(MediaLimits.check(-1L))
        assertFalse(MediaLimits.exceedsLimit(0L))
    }

    @Test
    fun `small payloads pass silently`() {
        assertNull(MediaLimits.check(1L))
        assertNull(MediaLimits.check(200L * kb))
        assertNull(MediaLimits.check(MediaLimits.WARN_SEND_BYTES - 1L))
    }

    @Test
    fun `warning starts exactly at the warn threshold and still allows sending`() {
        val text = MediaLimits.check(MediaLimits.WARN_SEND_BYTES)
        assertNotNull(text)
        assertTrue(text!!.contains("15.0 MB"))
        assertTrue(text.contains("分钟"))
        assertFalse(text.contains("超过"))
        assertTrue(MediaLimits.isWarning(MediaLimits.WARN_SEND_BYTES))
        assertFalse(MediaLimits.exceedsLimit(MediaLimits.WARN_SEND_BYTES))
    }

    @Test
    fun `exactly the maximum is allowed but warned about`() {
        val text = MediaLimits.check(MediaLimits.MAX_SEND_BYTES)
        assertNotNull(text)
        assertFalse(text!!.contains("超过"))
        assertFalse(MediaLimits.exceedsLimit(MediaLimits.MAX_SEND_BYTES))
        assertTrue(MediaLimits.isWarning(MediaLimits.MAX_SEND_BYTES))
    }

    @Test
    fun `one byte over the maximum is rejected with a reason`() {
        val size = MediaLimits.MAX_SEND_BYTES + 1L
        val text = MediaLimits.check(size)
        assertNotNull(text)
        assertTrue(text!!.contains("超过"))
        assertTrue(text.contains("50 MB"))
        assertTrue(MediaLimits.exceedsLimit(size))
        assertFalse(MediaLimits.isWarning(size))
    }

    @Test
    fun `oversized file reports the transfer time it would have taken`() {
        // 100 MB is about 11 minutes on this link.
        val minutes = MediaLimits.check(100L * mb)
        assertNotNull(minutes)
        assertTrue(minutes!!.contains("超过"))
        assertTrue(minutes.contains("分钟"))

        // 3 GB does not fit in minutes any more.
        val hours = MediaLimits.check(3L * 1024L * mb)
        assertNotNull(hours)
        assertTrue(hours!!.contains("超过"))
        assertTrue(hours.contains("小时"))
    }

    // ------------------------------------------------- Base64 offer budget

    @Test
    fun `12 KiB of base64 holds exactly 9216 bytes`() {
        assertEquals(9216, MediaFiles.base64ByteBudget(12 * 1024))
        assertEquals(12 * 1024, MediaFiles.base64Length(9216))
    }

    @Test
    fun `any jpeg inside the budget stays inside the offer limit`() {
        val budget = MediaFiles.base64ByteBudget(Bitmaps.THUMB_MAX_B64_CHARS)
        for (size in 1..budget) {
            assertTrue(
                "jpeg of $size bytes must fit in ${Bitmaps.THUMB_MAX_B64_CHARS} chars",
                MediaFiles.base64Length(size) <= Bitmaps.THUMB_MAX_B64_CHARS
            )
        }
        assertTrue(MediaFiles.base64Length(budget + 1) > Bitmaps.THUMB_MAX_B64_CHARS)
    }

    @Test
    fun `thumbnails cannot exceed 12 KiB of base64 text`() {
        assertEquals(12 * 1024, Bitmaps.THUMB_MAX_B64_CHARS)
    }

    // ------------------------------------------------------------ file names

    @Test
    fun `re-encoded images are named as jpeg`() {
        assertEquals("IMG_1234.jpg", MediaFiles.jpegName("IMG_1234.png"))
        assertEquals("IMG_1234.jpg", MediaFiles.jpegName("IMG_1234.JPEG"))
        assertEquals("image.jpg", MediaFiles.jpegName(null))
        assertEquals("image.jpg", MediaFiles.jpegName("..."))
    }

    @Test
    fun `names cannot escape the media directory`() {
        val cleaned = MediaFiles.sanitize("../../etc/passwd", "file")
        assertFalse(cleaned.contains("/"))
        assertFalse(cleaned.contains("\\"))
        assertFalse(cleaned.startsWith("."))
        assertEquals("file", MediaFiles.sanitize("", "file"))
        assertEquals("file", MediaFiles.sanitize("   ", "file"))
    }

    @Test
    fun `video names keep their extension or gain one from the mime type`() {
        assertEquals("clip.mp4", MediaFiles.videoName("clip.mp4", "video/mp4"))
        assertEquals("clip.mov", MediaFiles.videoName("clip.mov", "video/quicktime"))
        assertEquals("clip.mov", MediaFiles.videoName("clip", "video/quicktime"))
        assertEquals("video.mp4", MediaFiles.videoName(null, "video/mp4"))
    }

    @Test
    fun `thumbnail image sniffing accepts real images and rejects junk`() {
        assertTrue(MediaFiles.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)))
        assertFalse(MediaFiles.looksLikeImage(ByteArray(0)))
        assertFalse(MediaFiles.looksLikeImage("not an image".toByteArray()))
        assertFalse(MediaFiles.looksLikeImage(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `discarding an import removes exactly its payload and thumbnail`() {
        val payload = tempFile("liuli-payload", ".mp4", 32)
        val thumb = tempFile("liuli-thumb", ".jpg", 8)
        val bystander = tempFile("liuli-bystander", ".mp4", 4)

        val imported = ImportedMedia(
            filePath = payload.absolutePath,
            thumbPath = thumb.absolutePath,
            thumbB64 = null,
            name = "clip.mp4",
            mime = "video/mp4",
            size = 32L
        )

        assertTrue(discardImportedFiles(imported))
        assertFalse("payload must be gone", payload.exists())
        assertFalse("thumbnail must be gone", thumb.exists())
        assertTrue("unrelated files must survive", bystander.exists())

        // Idempotent: an import already cleaned up is still a success.
        assertTrue(discardImportedFiles(imported))
    }

    @Test
    fun `discarding an import without a thumbnail still removes the payload`() {
        val payload = tempFile("liuli-payload-plain", ".jpg", 4)
        val imported = ImportedMedia(
            filePath = payload.absolutePath,
            thumbPath = null,
            thumbB64 = null,
            name = "photo.jpg",
            mime = "image/jpeg",
            size = 4L
        )

        assertTrue(discardImportedFiles(imported))
        assertFalse(payload.exists())
    }

    private fun tempFile(prefix: String, suffix: String, bytes: Int): File =
        File.createTempFile(prefix, suffix).apply {
            writeBytes(ByteArray(bytes))
            deleteOnExit()
        }
}
