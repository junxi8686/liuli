package com.liuli.btchat.media.call

import com.liuli.btchat.media.VoiceRecorder
import kotlin.math.abs

/**
 * The pure half of the call pipeline: everything that can be decided without a microphone, a
 * speaker or a camera — and therefore everything that can be proven correct on a laptop.
 *
 * Frame geometry (16 kHz mono PCM-16, the narrow-band speech configuration both ends agreed on):
 * `20 ms = 320 samples = 640 bytes ≈ 32 KB/s` one way, so a two-way call costs about 64 KB/s of
 * the 100–200 KB/s RFCOMM link before any video.
 */
object CallAudioMath {

    const val SAMPLE_RATE = 16_000

    /** One frame of audio = one `Wire.TYPE_CALL` packet, sent every 20 ms. */
    const val FRAME_MS = 20

    const val SAMPLES_PER_FRAME = SAMPLE_RATE / 1000 * FRAME_MS

    /** 640 bytes: what one audio frame weighs on the wire. */
    const val FRAME_BYTES = SAMPLES_PER_FRAME * 2

    /** 32 KB/s one way. */
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2

    /** Jitter target: small enough to stay conversational, big enough to hide RFCOMM bursts. */
    const val JITTER_MIN_MS = 60
    const val JITTER_MAX_MS = 120

    /** 6 frames = 120 ms of slack; beyond that we drop the oldest instead of adding delay. */
    const val DEFAULT_CAPACITY_FRAMES = JITTER_MAX_MS / FRAME_MS

    /** Playback starts once 60 ms have collected, which is what hides the first hiccup. */
    const val DEFAULT_PREROLL_FRAMES = JITTER_MIN_MS / FRAME_MS

    fun silenceFrame(): ByteArray = ByteArray(FRAME_BYTES)

    /**
     * Splits a PCM run into whole [FRAME_MS] frames.
     *
     * A trailing partial frame is dropped rather than padded: `AudioRecord` delivers exactly one
     * frame per blocking read, so this only matters for odd sources, and a padded frame would
     * stretch time by a sample.
     */
    fun splitIntoFrames(
        pcm: ByteArray,
        offset: Int = 0,
        length: Int = pcm.size - offset,
        frameBytes: Int = FRAME_BYTES
    ): List<ByteArray> {
        if (frameBytes <= 0 || length <= 0 || offset >= pcm.size) return emptyList()
        val start = offset.coerceAtLeast(0)
        val end = (start + length).coerceAtMost(pcm.size)
        val frames = ArrayList<ByteArray>((end - start) / frameBytes)
        var cursor = start
        while (cursor + frameBytes <= end) {
            frames.add(pcm.copyOfRange(cursor, cursor + frameBytes))
            cursor += frameBytes
        }
        return frames
    }

    /**
     * Peak level of a PCM-16 frame as `0..100`, on the same −50 dB…0 dB curve the voice recorder
     * uses, so a speaking indicator looks identical in both places.
     */
    fun levelOf16(frame: ByteArray, offset: Int = 0, length: Int = frame.size - offset): Int {
        if (length <= 1 || offset >= frame.size) return 0
        val end = (offset + length).coerceAtMost(frame.size)
        var peak = 0
        var index = offset.coerceAtLeast(0)
        while (index + 1 < end) {
            val sample = ((frame[index + 1].toInt() shl 8) or (frame[index].toInt() and 0xFF))
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return VoiceRecorder.levelOf(peak)
    }

    /**
     * Converts the three planes of a `YUV_420_888` camera image into the NV21 layout that
     * `YuvImage.compressToJpeg` expects: full-resolution Y, then interleaved **V, U**.
     *
     * Camera planes are not tightly packed — rows can be padded ([yRowStride] > width) and chroma
     * can be planar ([uvPixelStride] == 1) or semi-planar ([uvPixelStride] == 2) — so every read
     * is bounds-checked. A truncated plane leaves the rest of [out] zeroed instead of throwing:
     * one short frame is not worth crashing a call over.
     *
     * @return [out] (`width * height * 3 / 2` bytes).
     */
    fun yuv420ToNv21(
        y: ByteArray,
        yRowStride: Int,
        u: ByteArray,
        v: ByteArray,
        uvRowStride: Int,
        uvPixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray = ByteArray(width * height * 3 / 2)
    ): ByteArray {
        if (width <= 0 || height <= 0 || out.size < width * height * 3 / 2) return out

        var position = 0
        for (row in 0 until height) {
            val source = row * yRowStride
            if (source < 0 || source + width > y.size) break
            System.arraycopy(y, source, out, position, width)
            position += width
        }

        val chromaRows = height / 2
        val chromaColumns = width / 2
        rows@ for (row in 0 until chromaRows) {
            val sourceRow = row * uvRowStride
            for (column in 0 until chromaColumns) {
                val index = sourceRow + column * uvPixelStride
                if (index < 0 || index >= u.size || index >= v.size || position + 1 >= out.size) break@rows
                out[position++] = v[index]
                out[position++] = u[index]
            }
        }
        return out
    }
}

/**
 * Jitter buffer for incoming call audio.
 *
 * The peer sends one 640-byte frame every 20 ms, but arrival is not that tidy: RFCOMM writes are
 * batched, and in a group call the audio is relayed by the group owner, so frames arrive in
 * bursts. This queue absorbs that burstiness with a deliberately small bound:
 *
 *  * **capacity 6 frames / 120 ms** — beyond that the *oldest* frame is dropped, because late
 *    audio is worse than missing audio ("宁丢不延").
 *  * **preroll 3 frames / 60 ms** — playback only starts once 60 ms have collected, which is what
 *    hides the first hiccup; the buffer then drains freely instead of re-priming on every gap.
 *  * **underrun** — an empty poll becomes a silent frame, so the speaker keeps its 20 ms cadence
 *    and never stutters or speeds up.
 *
 * Frames are copied on [push] because the transport reuses its read buffer as soon as the
 * callback returns. No reordering is attempted: RFCOMM is an ordered stream and reordering would
 * only add latency — a burst is played in arrival order.
 */
class AudioJitterBuffer(
    val capacityFrames: Int = CallAudioMath.DEFAULT_CAPACITY_FRAMES,
    val prerollFrames: Int = CallAudioMath.DEFAULT_PREROLL_FRAMES
) {

    private val ring = ArrayDeque<ByteArray>()

    private var primed = false

    /** Frames discarded because the queue was full. */
    var droppedFrames: Long = 0L
        private set

    /** Empty polls after priming. */
    var underruns: Long = 0L
        private set

    /** Silent frames handed out instead of real audio (includes priming). */
    var silenceFrames: Long = 0L
        private set

    val size: Int get() = ring.size

    /** `true` once enough frames have collected for playback to start. */
    val isPrimed: Boolean get() = primed

    /** How much audio is buffered, in milliseconds. */
    val depthMs: Int get() = ring.size * CallAudioMath.FRAME_MS

    /** Copies exact frames; empty input is ignored (a zero-length frame would break cadence). */
    fun push(frame: ByteArray) {
        if (frame.isEmpty()) return
        enqueue(frame.copyOf())
    }

    /**
     * Copies `[offset, offset + length)` into exactly one 20 ms frame: short data is zero-padded,
     * long data is truncated, so the player always writes whole frames and never drifts.
     */
    fun push(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val start = offset.coerceIn(0, data.size)
        val end = (start + length).coerceAtMost(data.size)
        if (end <= start) return
        val frame = ByteArray(CallAudioMath.FRAME_BYTES)
        val copy = (end - start).coerceAtMost(frame.size)
        System.arraycopy(data, start, frame, 0, copy)
        enqueue(frame)
    }

    private fun enqueue(frame: ByteArray) {
        if (capacityFrames <= 0) {
            droppedFrames++
            return
        }
        while (ring.size >= capacityFrames) {
            ring.removeFirst()
            droppedFrames++
        }
        ring.addLast(frame)
    }

    /**
     * Next frame, or `null` when there is nothing to play.
     *
     * Returns `null` (uncounted) while priming; after that an empty queue is a genuine underrun.
     */
    fun pull(): ByteArray? {
        if (!primed) {
            if (ring.size < minOf(prerollFrames, capacityFrames)) return null
            primed = true
        }
        val next = ring.removeFirstOrNull()
        if (next == null) {
            underruns++
            return null
        }
        return next
    }

    /** Next frame, or silence — what the playback loop actually writes. */
    fun pullOrSilence(): ByteArray {
        val frame = pull()
        if (frame != null) return frame
        silenceFrames++
        return CallAudioMath.silenceFrame()
    }

    /** Drops buffered audio (call ended, or after a long stall). */
    fun clear() {
        ring.clear()
        primed = false
    }

    /** [clear] plus counter reset, for tests and for a fresh call on the same instance. */
    fun reset() {
        clear()
        droppedFrames = 0L
        underruns = 0L
        silenceFrames = 0L
    }
}
