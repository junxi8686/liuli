package com.liuli.btchat.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Amplitude envelopes for voice bubbles.
 *
 * **These peaks come from a real decode.** [peaksOf] feeds the AAC track of the file through
 * `MediaExtractor` + `MediaCodec`, reads the decoded PCM and keeps the loudest frame in every
 * bucket, so the bars show what the recording actually contains. The decorative generator is
 * only a fallback for files the platform decoder refuses (a truncated take, an exotic
 * container), and [Peaks.decoded] tells the caller which of the two it got — nothing here
 * pretends a made-up shape is a measurement.
 *
 * The UI gets three entry points, cheapest first:
 * ```kotlin
 * var peaks by remember(path) { mutableStateOf(Waveform.cachedPeaks(path, 30)) }
 * LaunchedEffect(path) { peaks = Waveform.peaks(path, 30) }   // decode on IO, then cached
 * // optional first paint while decoding: Waveform.decorativePeaks(File(path), 30)
 * ```
 */
object Waveform {

    /** Bar count that looks right in a chat bubble. */
    const val DEFAULT_BUCKETS = 30

    /** Quiet recordings keep their shape instead of being normalised up to full scale. */
    private const val NOISE_FLOOR = 0.08f

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_DECODE_ITERATIONS = 200_000
    private const val DECODE_BUDGET_NANOS = 5_000_000_000L

    /** Peaks plus where they came from. */
    class Peaks(val values: FloatArray, val decoded: Boolean)

    /**
     * Peaks of [file] as [buckets] values in `0..1`.
     *
     * Blocking: it decodes the audio, so call it from a background thread (or use [peaks]).
     * Falls back to [decorativePeaks] when the file cannot be decoded.
     */
    fun peaksOf(file: File, buckets: Int = DEFAULT_BUCKETS): FloatArray = peaksOfDetailed(file, buckets).values

    /** [peaksOf] without the ambiguity: [Peaks.decoded] says whether a decoder really ran. */
    fun peaksOfDetailed(file: File, buckets: Int = DEFAULT_BUCKETS): Peaks {
        val count = buckets.coerceAtLeast(1)
        val decoded = try {
            decode(file, count)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
        return if (decoded != null) Peaks(decoded, true) else Peaks(decorativePeaks(file, count), false)
    }

    /**
     * Peaks for a stored payload, decoded on [Dispatchers.IO] and memoised.
     *
     * A chat list scrolls over the same voice notes repeatedly, and decoding each one on every
     * recomposition would be visible; the cache key includes the file's size and modification
     * time, so a replaced file is never served a stale envelope.
     */
    suspend fun peaks(path: String, buckets: Int = DEFAULT_BUCKETS): FloatArray {
        val count = buckets.coerceAtLeast(1)
        return withContext(Dispatchers.IO) {
            val file = File(path)
            cachedPeaks(path, count)?.let { return@withContext it }
            val values = peaksOf(file, count)
            store(file, count, values)
            values
        }
    }

    /** Cache-only lookup, safe to call during composition; `null` until [peaks] has run once. */
    fun cachedPeaks(path: String, buckets: Int = DEFAULT_BUCKETS): FloatArray? {
        val count = buckets.coerceAtLeast(1)
        val file = File(path)
        if (!file.isFile) return null
        return load(file, count)
    }

    /**
     * A stable, hash-driven envelope for [file] — **decorative, not a measurement**.
     *
     * Same file in, same bars out, shaped like speech (a random walk with a gentle envelope) so a
     * bubble still looks alive before (or instead of) a real decode. Never presents itself as
     * real: callers that care use [peaksOfDetailed].
     */
    fun decorativePeaks(file: File, buckets: Int = DEFAULT_BUCKETS): FloatArray {
        val count = buckets.coerceAtLeast(1)
        var state = (file.name.hashCode().toLong() and 0xFFFFFFFFL) xor
            (file.length() * 2_654_435_761L) xor
            (file.lastModified() * 40_503L)
        if (state == 0L) state = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

        val out = FloatArray(count)
        var level = 0.45f
        for (i in 0 until count) {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            val r = (state ushr 11).toDouble() / (1L shl 53).toDouble()
            level = (level + (r.toFloat() - 0.5f) * 0.5f).coerceIn(0.12f, 1f)
            out[i] = level
        }
        // Smooth neighbours so the bars read as an envelope rather than as noise.
        for (i in 1 until count) out[i] = (out[i] * 0.7f + out[i - 1] * 0.3f).coerceIn(0f, 1f)
        return out
    }

    // ----------------------------------------------------------------- decode

    private fun decode(file: File, buckets: Int): FloatArray? {
        if (!file.isFile || file.length() <= 0L) return null

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)

            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                .getOrDefault(1).coerceAtLeast(1)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val accumulator = PeakBuckets(buckets)
            val info = MediaCodec.BufferInfo()
            var pcmFloat = false
            var inputDone = false
            var outputDone = false
            var iterations = 0
            val deadline = System.nanoTime() + DECODE_BUDGET_NANOS

            while (!outputDone && iterations++ < MAX_DECODE_ITERATIONS && System.nanoTime() < deadline) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        if (buffer == null) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        pcmFloat = runCatching {
                            decoder.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                        }.getOrDefault(false)
                    }

                    outIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            decoder.getOutputBuffer(outIndex)?.let { pcm ->
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                if (pcmFloat) feedFloat(accumulator, pcm, channels) else feed16(accumulator, pcm, channels)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                    }

                    // INFO_TRY_AGAIN_LATER — nothing to collect yet, keep pumping.
                }
            }
            return accumulator.normalized()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 16-bit PCM, downmixed across channels by taking the loudest sample of each frame. */
    private fun feed16(accumulator: PeakBuckets, pcm: ByteBuffer, channels: Int) {
        val samples = pcm.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = samples.remaining() / channels
        for (frame in 0 until frames) {
            var loudest = 0
            for (channel in 0 until channels) {
                val value = samples.get(frame * channels + channel).toInt()
                if (abs(value) > abs(loudest)) loudest = value
            }
            accumulator.add(loudest / 32_768f)
        }
    }

    /** 32-bit float PCM, for decoders configured that way. */
    private fun feedFloat(accumulator: PeakBuckets, pcm: ByteBuffer, channels: Int) {
        val samples = pcm.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val frames = samples.remaining() / channels
        for (frame in 0 until frames) {
            var loudest = 0f
            for (channel in 0 until channels) {
                val value = samples.get(frame * channels + channel)
                if (abs(value) > abs(loudest)) loudest = value
            }
            accumulator.add(loudest)
        }
    }

    // ------------------------------------------------------------------ cache

    private fun cacheKey(file: File, buckets: Int): String =
        "wave:${file.absolutePath}:${file.lastModified()}:${file.length()}:$buckets"

    private fun store(file: File, buckets: Int, values: FloatArray) {
        runCatching {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (value in values) buffer.putFloat(value)
            ThumbCache.put(cacheKey(file, buckets), buffer.array())
        }
    }

    private fun load(file: File, buckets: Int): FloatArray? = try {
        val bytes = ThumbCache.get(cacheKey(file, buckets))
        if (bytes == null || bytes.size != buckets * 4) {
            null
        } else {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(buckets) { buffer.getFloat() }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Buckets decoded PCM frames into per-bucket maxima and normalises the result.
 *
 * Frames are folded into fixed-size chunks as they arrive and the chunks are resampled to
 * [buckets] at the end, so the envelope spans the whole clip without knowing its duration up
 * front (and without keeping every frame in memory — a 60 s note costs a few kilobytes).
 *
 * Pure Kotlin on purpose: the bucketing and normalisation are the part of the waveform that can
 * be wrong in a way users notice, so they are unit-tested without a device.
 *
 * Normalisation scales the loudest bucket to 1 but never amplifies below the noise floor, which
 * keeps a whisper looking quiet while still filling the bubble for normal speech.
 */
internal class PeakBuckets(buckets: Int, private val framesPerChunk: Int = DEFAULT_FRAMES_PER_CHUNK) {

    private val buckets = buckets.coerceAtLeast(1)

    private var chunks = FloatArray(INITIAL_CHUNKS)
    private var chunkCount = 0
    private var current = 0f
    private var inChunk = 0
    private var peak = 0f

    /** Feeds one downmixed frame; [amplitude] is the sample value scaled to `-1..1`. */
    fun add(amplitude: Float) {
        val value = abs(amplitude).coerceIn(0f, 1f)
        if (value > current) current = value
        if (value > peak) peak = value
        if (++inChunk >= framesPerChunk) flush()
    }

    /** Per-bucket maxima, loudest bucket at 1.0. Idempotent. */
    fun normalized(): FloatArray {
        flush()
        if (chunkCount == 0 || peak <= 0f) return FloatArray(buckets)
        val scale = maxOf(peak, NOISE_FLOOR)
        return FloatArray(buckets) { bucket ->
            val from = bucket.toLong() * chunkCount / buckets
            val to = (((bucket + 1).toLong() * chunkCount) / buckets).coerceAtLeast(from + 1)
            var loudest = 0f
            var index = from
            while (index < to && index < chunkCount) {
                val value = chunks[index.toInt()]
                if (value > loudest) loudest = value
                index++
            }
            (loudest / scale).coerceIn(0f, 1f)
        }
    }

    /** Chunks buffered so far, including the partial one once [normalized] has run. */
    val chunkTotal: Int get() = chunkCount + if (inChunk > 0) 1 else 0

    private fun flush() {
        if (inChunk == 0) return
        if (chunkCount == chunks.size) chunks = chunks.copyOf(chunks.size * 2)
        chunks[chunkCount++] = current
        current = 0f
        inChunk = 0
    }

    private companion object {
        const val NOISE_FLOOR = 0.08f

        /** ~46 ms at 44.1 kHz: fine enough that a bucket boundary is never audible. */
        const val DEFAULT_FRAMES_PER_CHUNK = 2048
        const val INITIAL_CHUNKS = 64
    }
}
