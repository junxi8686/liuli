package com.liuli.btchat.media

import com.liuli.btchat.media.call.AudioJitterBuffer
import com.liuli.btchat.media.call.CallAudioMath
import com.liuli.btchat.media.call.CallStats
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provable half of the call pipeline.
 *
 * A microphone, a speaker and a camera cannot be tested on a laptop, but everything that decides
 * what those devices are *told to do* — frame geometry, jitter absorption, PCM framing and the
 * YUV index maths — is pure, and that is exactly where a call breaks in ways users notice
 * (stutter, drift, a green picture). Those paths are pinned down here.
 */
class CallMediaMathTest {

    private fun frame(marker: Int) = ByteArray(CallAudioMath.FRAME_BYTES) { marker.toByte() }

    // ---------------------------------------------------------- frame geometry

    @Test
    fun `frame geometry is the 32 KB per second both ends agreed on`() {
        assertEquals(16_000, CallAudioMath.SAMPLE_RATE)
        assertEquals(20, CallAudioMath.FRAME_MS)
        assertEquals(320, CallAudioMath.SAMPLES_PER_FRAME)
        assertEquals(640, CallAudioMath.FRAME_BYTES)
        // 640 bytes every 20 ms is exactly 50 frames per second.
        assertEquals(CallAudioMath.BYTES_PER_SECOND, CallAudioMath.FRAME_BYTES * (1_000 / CallAudioMath.FRAME_MS))
        assertEquals(32_000, CallAudioMath.BYTES_PER_SECOND)
    }

    @Test
    fun `the jitter target band is 60 to 120 milliseconds`() {
        assertEquals(3, CallAudioMath.DEFAULT_PREROLL_FRAMES)
        assertEquals(6, CallAudioMath.DEFAULT_CAPACITY_FRAMES)
        assertEquals(CallAudioMath.JITTER_MIN_MS, CallAudioMath.DEFAULT_PREROLL_FRAMES * CallAudioMath.FRAME_MS)
        assertEquals(CallAudioMath.JITTER_MAX_MS, CallAudioMath.DEFAULT_CAPACITY_FRAMES * CallAudioMath.FRAME_MS)
    }

    // ------------------------------------------------------------- pcm framing

    @Test
    fun `pcm is cut into whole frames and a partial tail is dropped`() {
        val pcm = ByteArray(CallAudioMath.FRAME_BYTES * 2 + 100)
        val frames = CallAudioMath.splitIntoFrames(pcm)
        assertEquals(2, frames.size)
        assertEquals(CallAudioMath.FRAME_BYTES, frames[0].size)
    }

    @Test
    fun `splitting respects offset and length`() {
        val pcm = ByteArray(CallAudioMath.FRAME_BYTES * 3) { (it % 251).toByte() }
        val frames = CallAudioMath.splitIntoFrames(
            pcm,
            offset = CallAudioMath.FRAME_BYTES,
            length = CallAudioMath.FRAME_BYTES * 2
        )
        assertEquals(2, frames.size)
        assertArrayEquals(
            pcm.copyOfRange(CallAudioMath.FRAME_BYTES, CallAudioMath.FRAME_BYTES * 2),
            frames[0]
        )
    }

    @Test
    fun `splitting an empty or too short buffer yields nothing`() {
        assertTrue(CallAudioMath.splitIntoFrames(ByteArray(0)).isEmpty())
        assertTrue(CallAudioMath.splitIntoFrames(ByteArray(CallAudioMath.FRAME_BYTES - 1)).isEmpty())
        assertTrue(CallAudioMath.splitIntoFrames(ByteArray(CallAudioMath.FRAME_BYTES), offset = CallAudioMath.FRAME_BYTES).isEmpty())
    }

    // ------------------------------------------------------------- level meter

    @Test
    fun `the level meter reads silence, speech and full scale correctly`() {
        assertEquals(0, CallAudioMath.levelOf16(ByteArray(CallAudioMath.FRAME_BYTES)))

        val loud = ByteArray(CallAudioMath.FRAME_BYTES)
        loud[0] = 0xFF.toByte()
        loud[1] = 0x7F
        assertEquals(100, CallAudioMath.levelOf16(loud))

        val speech = ByteArray(CallAudioMath.FRAME_BYTES)
        val tenth = 3_277 // -20 dBFS
        speech[0] = (tenth and 0xFF).toByte()
        speech[1] = ((tenth shr 8) and 0xFF).toByte()
        val level = CallAudioMath.levelOf16(speech)
        assertTrue("expected about 60 but was $level", level in 58..62)
    }

    @Test
    fun `a level read of a bogus range stays in bounds`() {
        val frame = ByteArray(CallAudioMath.FRAME_BYTES) { 0x40 }
        assertEquals(0, CallAudioMath.levelOf16(frame, offset = -10, length = 5))
        assertEquals(0, CallAudioMath.levelOf16(frame, offset = CallAudioMath.FRAME_BYTES + 10, length = 5))
        assertTrue(CallAudioMath.levelOf16(frame, offset = 0, length = 1_000_000) in 0..100)
    }

    // ---------------------------------------------------------- jitter buffer

    @Test
    fun `an empty buffer never overflows and hands out silence`() {
        val buffer = AudioJitterBuffer()

        assertNull(buffer.pull())
        assertEquals(0, buffer.size)
        assertEquals(0, buffer.depthMs)
        assertFalse(buffer.isPrimed)

        repeat(5) {
            val silence = buffer.pullOrSilence()
            assertEquals(CallAudioMath.FRAME_BYTES, silence.size)
            assertTrue(silence.all { it == 0.toByte() })
        }
        assertEquals(5, buffer.silenceFrames)
        assertFalse(buffer.isPrimed)
    }

    @Test
    fun `playback waits for the preroll and is not counted as an underrun`() {
        val buffer = AudioJitterBuffer(capacityFrames = 6, prerollFrames = 3)
        buffer.push(frame(1))
        buffer.push(frame(2))

        assertNull(buffer.pull())
        assertEquals(0, buffer.underruns)

        buffer.push(frame(3))
        assertEquals(1, buffer.pull()!![0].toInt())
        assertEquals(2, buffer.pull()!![0].toInt())
        assertEquals(3, buffer.pull()!![0].toInt())
    }

    @Test
    fun `an underrun after priming is counted and padded with silence`() {
        val buffer = AudioJitterBuffer(capacityFrames = 6, prerollFrames = 3)
        repeat(3) { buffer.push(frame(it + 1)) }
        repeat(3) { assertNotNull(buffer.pull()) }

        assertNull(buffer.pull())
        assertEquals(1, buffer.underruns)

        val silence = buffer.pullOrSilence()
        assertTrue(silence.all { it == 0.toByte() })
        assertEquals(2, buffer.underruns)
        assertEquals(1, buffer.silenceFrames)
    }

    @Test
    fun `an overload throws away the oldest frames and keeps the newest`() {
        val buffer = AudioJitterBuffer(capacityFrames = 4, prerollFrames = 1)
        for (marker in 1..10) buffer.push(frame(marker))

        assertEquals(4, buffer.size)
        assertEquals(6, buffer.droppedFrames)

        val drained = (1..4).map { buffer.pull()!![0].toInt() }
        assertEquals(listOf(7, 8, 9, 10), drained)
    }

    @Test
    fun `an out of order burst is absorbed without crashing`() {
        val buffer = AudioJitterBuffer()
        for (marker in (1..30).shuffled(Random(7))) buffer.push(frame(marker))

        assertTrue(buffer.size <= CallAudioMath.DEFAULT_CAPACITY_FRAMES)
        assertTrue(buffer.droppedFrames > 0)

        var drained = 0
        while (true) {
            val next = buffer.pull() ?: break
            assertEquals(CallAudioMath.FRAME_BYTES, next.size)
            drained++
        }
        assertTrue(drained <= CallAudioMath.DEFAULT_CAPACITY_FRAMES)

        // A late arrival after the queue drained is simply queued again.
        buffer.push(frame(99))
        assertNotNull(buffer.pull())
        assertNotNull(buffer.pullOrSilence())
    }

    @Test
    fun `a short or long remote frame is normalised to exactly one 20 ms frame`() {
        val buffer = AudioJitterBuffer(capacityFrames = 6, prerollFrames = 1)

        val short = ByteArray(100) { 5 }
        buffer.push(short, 0, short.size)
        val padded = buffer.pull()!!
        assertEquals(CallAudioMath.FRAME_BYTES, padded.size)
        assertEquals(5, padded[99].toInt())
        assertEquals(0, padded[100].toInt())

        val long = ByteArray(CallAudioMath.FRAME_BYTES * 2) { 7 }
        buffer.push(long, 0, long.size)
        assertEquals(CallAudioMath.FRAME_BYTES, buffer.pull()!!.size)
    }

    @Test
    fun `bogus offsets are ignored instead of indexing out of bounds`() {
        val buffer = AudioJitterBuffer(capacityFrames = 4, prerollFrames = 1)

        buffer.push(ByteArray(10), -5, 100) // clamped, enqueued
        buffer.push(ByteArray(10), 100, 5)  // entirely past the end
        buffer.push(ByteArray(10), 0, -1)   // negative length
        buffer.push(ByteArray(0), 0, 0)     // empty

        assertEquals(1, buffer.size)
    }

    @Test
    fun `clear resets priming and reset clears the counters`() {
        val buffer = AudioJitterBuffer(capacityFrames = 6, prerollFrames = 2)
        // Two more than capacity, so the drop-oldest path is exercised too.
        repeat(8) { buffer.push(frame(it + 1)) }
        buffer.pull()
        assertTrue(buffer.isPrimed)
        assertTrue(buffer.droppedFrames > 0)

        buffer.clear()
        assertEquals(0, buffer.size)
        assertFalse(buffer.isPrimed)

        buffer.reset()
        assertEquals(0L, buffer.droppedFrames)
        assertEquals(0L, buffer.underruns)
        assertEquals(0L, buffer.silenceFrames)
    }

    // ------------------------------------------------------------ yuv to nv21

    @Test
    fun `semi planar chroma is interleaved as v then u`() {
        val y = ByteArray(8) { (it + 1).toByte() }
        val u = byteArrayOf(10, 99, 11, 99, 12, 99, 13, 99)
        val v = byteArrayOf(20, 99, 21, 99, 22, 99, 23, 99)

        val out = CallAudioMath.yuv420ToNv21(y, 4, u, v, uvRowStride = 4, uvPixelStride = 2, width = 4, height = 2)

        assertEquals(4 * 2 * 3 / 2, out.size)
        assertArrayEquals(y, out.copyOfRange(0, 8))
        assertArrayEquals(byteArrayOf(20, 10, 21, 11), out.copyOfRange(8, 12))
    }

    @Test
    fun `planar and semi planar chroma produce the same nv21`() {
        val width = 4
        val height = 2
        val y = ByteArray(width * height) { 3 }

        val planar = CallAudioMath.yuv420ToNv21(
            y, width,
            byteArrayOf(10, 11, 12, 13), byteArrayOf(20, 21, 22, 23),
            uvRowStride = 2, uvPixelStride = 1,
            width = width, height = height
        )
        val semiPlanar = CallAudioMath.yuv420ToNv21(
            y, width,
            byteArrayOf(10, 99, 11, 99, 12, 99, 13, 99), byteArrayOf(20, 99, 21, 99, 22, 99, 23, 99),
            uvRowStride = 4, uvPixelStride = 2,
            width = width, height = height
        )

        assertArrayEquals(planar, semiPlanar)
        assertArrayEquals(byteArrayOf(20, 10, 21, 11), planar.copyOfRange(width * height, planar.size))
    }

    @Test
    fun `padded luma rows are skipped and a truncated plane cannot crash`() {
        // 4x2 image stored with 8-byte rows: four bytes of image, four of padding.
        val y = byteArrayOf(1, 2, 3, 4, 91, 92, 93, 94, 5, 6, 7, 8, 95, 96, 97, 98)
        val u = ByteArray(8) { 10 }
        val v = ByteArray(8) { 20 }

        val out = CallAudioMath.yuv420ToNv21(y, 8, u, v, uvRowStride = 4, uvPixelStride = 2, width = 4, height = 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), out.copyOfRange(0, 8))

        // Luma plane cut short: the copied row survives, the rest stays zeroed, nothing throws.
        // (Chroma is empty here so the luma truncation is what the assertions see.)
        val truncated = CallAudioMath.yuv420ToNv21(
            ByteArray(4) { 9 }, 8,
            ByteArray(0), ByteArray(0),
            uvRowStride = 4, uvPixelStride = 2,
            width = 4, height = 4
        )
        assertEquals(4 * 4 * 3 / 2, truncated.size)
        assertEquals(9, truncated[0].toInt())
        assertTrue(truncated.copyOfRange(4, truncated.size).all { it == 0.toByte() })

        // Chroma cut short: same promise.
        val noChroma = CallAudioMath.yuv420ToNv21(ByteArray(64) { 1 }, 8, ByteArray(0), ByteArray(0), uvRowStride = 4, uvPixelStride = 2, width = 8, height = 8)
        assertEquals(8 * 8 * 3 / 2, noChroma.size)
        assertTrue(noChroma.copyOfRange(64, noChroma.size).all { it == 0.toByte() })
    }

    @Test
    fun `an output buffer that is too small is returned untouched`() {
        val out = ByteArray(4)
        val result = CallAudioMath.yuv420ToNv21(
            ByteArray(64), 8, ByteArray(16), ByteArray(16),
            uvRowStride = 8, uvPixelStride = 2,
            width = 8, height = 8,
            out = out
        )
        assertSame(out, result)
        assertArrayEquals(ByteArray(4), out)
    }

    // --------------------------------------------------------------- ui labels

    @Test
    fun `the low frame rate note follows the measured rate and only then`() {
        assertNull(CallStats(active = true, videoFramesSent = 10, videoFps = 8f).videoQualityNote)

        val slow = CallStats(active = true, videoFramesSent = 10, videoFps = 3f).videoQualityNote
        assertNotNull(slow)
        assertTrue(slow!!.contains("低帧率画面"))

        val borderline = CallStats(active = true, videoFramesSent = 10, videoFps = 5f).videoQualityNote
        assertNotNull(borderline)
        assertTrue(borderline!!.contains("低帧率画面"))

        // Outside a call, or with the camera off, there is nothing honest to claim.
        assertNull(CallStats(active = false, videoFps = 1f).videoQualityNote)
        assertEquals("摄像头未开启", CallStats(active = true, videoFramesSent = 0, videoFps = 0f).videoQualityNote)
    }
}
