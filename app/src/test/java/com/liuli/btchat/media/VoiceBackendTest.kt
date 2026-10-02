package com.liuli.btchat.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the voice backend: the level meter, the waveform bucketing/normalisation,
 * the decorative fallback and the naming/bridging rules the UI and the transport depend on.
 *
 * The audio stack itself (`MediaRecorder`, `MediaPlayer`, `MediaCodec`) needs a device; what is
 * tested here is every decision that is made *around* it.
 */
class VoiceBackendTest {

    // -------------------------------------------------------------- level meter

    @Test
    fun `silence maps to an empty bar`() {
        assertEquals(0, VoiceRecorder.levelOf(0))
        assertEquals(0, VoiceRecorder.levelOf(-12))
        assertEquals(0, VoiceRecorder.levelOf(30))
    }

    @Test
    fun `full scale maps to a full bar`() {
        assertEquals(100, VoiceRecorder.levelOf(32_767))
        assertEquals(100, VoiceRecorder.levelOf(40_000))
    }

    @Test
    fun `speech at minus twenty dB sits in the middle of the bar`() {
        // A tenth of full scale is -20 dB, which the -50..0 dB window puts at ~60.
        val level = VoiceRecorder.levelOf(3_277)
        assertTrue("expected ~60 but was $level", level in 58..62)
    }

    @Test
    fun `louder input never meters lower`() {
        var previous = -1
        for (raw in 0..32_767 step 257) {
            val level = VoiceRecorder.levelOf(raw)
            assertTrue("level regressed at raw=$raw", level >= previous)
            assertTrue(level in 0..100)
            previous = level
        }
    }

    // ------------------------------------------------------------- peak buckets

    @Test
    fun `each bucket keeps the loudest frame of its slice`() {
        val buckets = PeakBuckets(buckets = 5, framesPerChunk = 2)
        floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f, 0.8f, 0.1f, 0.4f, 0.4f, 0.5f, 1.0f).forEach { buckets.add(it) }

        val peaks = buckets.normalized()
        assertEquals(5, peaks.size)
        assertEquals(0.9f, peaks[0], 1e-4f)
        assertEquals(0.3f, peaks[1], 1e-4f)
        assertEquals(0.8f, peaks[2], 1e-4f)
        assertEquals(0.4f, peaks[3], 1e-4f)
        assertEquals(1.0f, peaks[4], 1e-4f)
    }

    @Test
    fun `chunks are resampled across every bucket regardless of duration`() {
        val buckets = PeakBuckets(buckets = 3, framesPerChunk = 1)
        for (step in 1..10) buckets.add(step / 10f)

        val peaks = buckets.normalized()
        assertEquals(10, buckets.chunkTotal)
        assertEquals(0.3f, peaks[0], 1e-4f)
        assertEquals(0.6f, peaks[1], 1e-4f)
        assertEquals(1.0f, peaks[2], 1e-4f)
    }

    @Test
    fun `silence stays flat`() {
        val buckets = PeakBuckets(buckets = 4, framesPerChunk = 2)
        repeat(8) { buckets.add(0f) }
        assertArrayEquals(FloatArray(4), buckets.normalized(), 1e-6f)
    }

    @Test
    fun `out of range samples are clamped instead of exploding the bar`() {
        val buckets = PeakBuckets(buckets = 2, framesPerChunk = 1)
        buckets.add(5f)
        buckets.add(-7f)

        val peaks = buckets.normalized()
        assertEquals(1f, peaks[0], 1e-4f)
        assertEquals(1f, peaks[1], 1e-4f)
    }

    @Test
    fun `a whisper is not normalised up to full scale`() {
        val buckets = PeakBuckets(buckets = 2, framesPerChunk = 1)
        buckets.add(0.04f)
        buckets.add(0.02f)

        val peaks = buckets.normalized()
        // Peak 0.04 is below the 0.08 noise floor, so the bars stay short.
        assertEquals(0.5f, peaks[0], 1e-4f)
        assertEquals(0.25f, peaks[1], 1e-4f)
    }

    @Test
    fun `normalisation is idempotent`() {
        val buckets = PeakBuckets(buckets = 3, framesPerChunk = 2)
        for (step in 1..6) buckets.add(step / 6f)
        assertArrayEquals(buckets.normalized(), buckets.normalized(), 0f)
    }

    @Test
    fun `a degenerate bucket count still produces a usable envelope`() {
        val buckets = PeakBuckets(buckets = 0, framesPerChunk = 4)
        buckets.add(0.5f)
        assertEquals(1, buckets.normalized().size)
    }

    // -------------------------------------------------------- decorative peaks

    @Test
    fun `decorative peaks are stable per file`() {
        val file = tempFile("liuli-wave-a", 128)
        val first = Waveform.decorativePeaks(file, 24)
        val second = Waveform.decorativePeaks(file, 24)

        assertEquals(24, first.size)
        assertTrue("decorative peaks must be deterministic", first.contentEquals(second))
    }

    @Test
    fun `decorative peaks stay in range and are not flat`() {
        val peaks = Waveform.decorativePeaks(tempFile("liuli-wave-b", 256), 32)

        assertEquals(32, peaks.size)
        assertTrue(peaks.all { it in 0f..1f })
        assertTrue("decorative peaks should look like an envelope", peaks.max() > 0.5f)
    }

    @Test
    fun `different files get different decorative shapes`() {
        val first = Waveform.decorativePeaks(tempFile("liuli-wave-c", 100), 20)
        val second = Waveform.decorativePeaks(tempFile("liuli-wave-d", 900), 20)
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `a zero bucket count is coerced to one bar`() {
        assertEquals(1, Waveform.decorativePeaks(tempFile("liuli-wave-e", 10), 0).size)
    }

    @Test
    fun `an unreadable file falls back to decorative peaks and says so`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "liuli-missing-${System.nanoTime()}.m4a")

        val peaks = Waveform.peaksOfDetailed(missing, 16)
        assertEquals(16, peaks.values.size)
        assertFalse("a file that cannot be decoded must not claim to be a real waveform", peaks.decoded)
        assertTrue(peaks.values.all { it in 0f..1f })
    }

    // ------------------------------------------------------------ names/bridge

    @Test
    fun `voice file names are short, stable and playable`() {
        assertEquals("voice_abcdef12.m4a", MediaFiles.voiceFileName("abcdef12-3456-7890-abcd-ef1234567890"))
        assertEquals("voice_clip.m4a", MediaFiles.voiceFileName(""))
        assertEquals("voice_clip.m4a", MediaFiles.voiceFileName("---"))
        assertEquals("audio/mp4", MediaFiles.mimeFromName("voice_ab12.m4a"))
    }

    @Test
    fun `a finished take bridges to the attachment model the engine wants`() {
        val take = RecordedVoice(
            filePath = "abcdef12-3456__voice_abcdef12.m4a",
            durationMs = 2_500L,
            size = 20_000L,
            sha256 = "deadbeef"
        )

        val media = take.toImportedMedia()
        assertEquals("abcdef12-3456__voice_abcdef12.m4a", media.filePath)
        assertEquals("voice_abcdef12.m4a", media.name)
        assertEquals("audio/mp4", media.mime)
        assertEquals(20_000L, media.size)
        assertEquals(2_500L, media.durationMs)
        assertEquals("deadbeef", media.sha256)
        assertNull(media.thumbPath)
        assertNull(media.thumbB64)
    }

    private fun tempFile(prefix: String, bytes: Int): File =
        File.createTempFile(prefix, ".m4a").apply {
            writeBytes(ByteArray(bytes))
            deleteOnExit()
        }
}
