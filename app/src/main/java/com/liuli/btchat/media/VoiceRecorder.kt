package com.liuli.btchat.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log10

/**
 * Holds a take the user just recorded.
 *
 * [sha256] is streamed from the finished file and travels with the attachment, so the receiver
 * can verify the payload; [durationMs] is measured with the monotonic clock rather than trusted
 * to the encoder, whose container duration is unreliable for very short clips.
 */
data class RecordedVoice(
    val filePath: String,
    val durationMs: Long,
    val size: Long,
    val sha256: String
) {
    /**
     * Bridges to the transport: [ImportedMedia] is what `ChatEngine.sendMedia` expects, and an
     * `audio/…` MIME is what makes the engine file it as [com.liuli.btchat.core.MsgKind.VOICE].
     */
    fun toImportedMedia(): ImportedMedia {
        val file = File(filePath)
        return ImportedMedia(
            filePath = filePath,
            thumbPath = null,
            thumbB64 = null,
            name = file.name.substringAfter("__", file.name),
            mime = VoiceRecorder.MIME,
            size = size,
            width = 0,
            height = 0,
            durationMs = durationMs,
            sha256 = sha256
        )
    }
}

/**
 * Records one voice message at a time.
 *
 * The output is **AAC in an MPEG-4 container, mono, 64 kbit/s, 44.1 kHz** — deliberately small,
 * because the payload still has to cross a 100–200 KB/s RFCOMM link:
 *
 * | length | payload |
 * |---|---|
 * | 1 s | ~8 KB |
 * | 10 s | ~80 KB |
 * | 60 s (the ceiling) | ~480 KB |
 *
 * The recorder owns the whole take: it mints the file name, meters the input level for the
 * waveform bar, and deletes the file on [cancel] so a cancelled press leaves nothing behind.
 * It never asks for the microphone permission itself — it only reports whether it has it, and
 * the screen is responsible for the request flow.
 *
 * Typical press-and-hold use:
 * ```kotlin
 * val recorder = rememberVoiceRecorder()
 * if (recorder.start()) { /* poll amplitude / elapsedMs while held */ }
 * val take = recorder.stop()                                   // finger lifted
 * if (take != null && take.durationMs >= VoiceRecorder.MIN_VOICE_MS) {
 *     engine.sendMedia(convId, take.toImportedMedia())
 * } else take?.let { recorder.discard(it) }                    // too short: a mis-tap
 * ```
 */
class VoiceRecorder(private val ctx: Context) {

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var recorder: MediaRecorder? = null

    @Volatile
    private var target: File? = null

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var smoothed = 0

    private val autoStop = Runnable {
        val take = stop()
        onAutoStop?.invoke(take)
    }

    /**
     * Called on the main thread when [MAX_VOICE_MS] stops the recording by itself, so the UI can
     * send the take without the user still holding the button.
     */
    var onAutoStop: ((RecordedVoice?) -> Unit)? = null

    val isRecording: Boolean get() = recorder != null

    /** Milliseconds since [start]; `0` while idle. */
    val elapsedMs: Long
        get() = if (recorder == null) 0L else (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)

    /** `true` once the take reached the 60 s ceiling (the recorder also stops itself). */
    val reachedLimit: Boolean get() = isRecording && elapsedMs >= MAX_VOICE_MS

    /**
     * Current input level as `0..100`, for the recording bar.
     *
     * `getMaxAmplitude()` is linear and speech sits in its bottom few percent, so the raw value is
     * mapped through a −50 dB…0 dB curve; the result then decays by 40%/poll for a fast attack and
     * a smooth release. Cheap and non-blocking — safe to poll every frame.
     */
    val amplitude: Int
        get() {
            val active = recorder ?: return 0
            val raw = runCatching { active.maxAmplitude }.getOrDefault(0)
            val level = levelOf(raw)
            smoothed = maxOf(level, smoothed * DECAY_PERCENT / 100)
            return smoothed
        }

    /**
     * Starts a take.
     *
     * @return `false` when the microphone permission is missing, the device is busy, or a
     * recording is already running (this never creates a second [MediaRecorder]).
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (recorder != null) return false
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false
            }

            val id = newId()
            val file = MediaLayout.payloadFile(ctx, id, MediaFiles.voiceFileName(id))
            val created = MediaRecorder(ctx)
            return try {
                created.setAudioSource(MediaRecorder.AudioSource.MIC)
                created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                created.setAudioEncodingBitRate(BIT_RATE)
                created.setAudioSamplingRate(SAMPLE_RATE)
                created.setAudioChannels(CHANNELS)
                created.setOutputFile(file.absolutePath)
                created.prepare()
                created.start()

                recorder = created
                target = file
                startedAt = SystemClock.elapsedRealtime()
                smoothed = 0
                handler.postDelayed(autoStop, MAX_VOICE_MS)
                true
            } catch (e: Exception) {
                runCatching { created.release() }
                runCatching { file.delete() }
                recorder = null
                target = null
                startedAt = 0L
                false
            }
        }
    }

    /**
     * Stops the take and describes the finished file.
     *
     * Returns `null` when nothing was recording, or when the encoder produced nothing usable
     * (a hard tap can end a take before the first frame exists). A take shorter than
     * [MIN_VOICE_MS] is still returned — the UI decides whether it was a mis-tap and calls
     * [discard]; a rejected take is never silently deleted here.
     *
     * The finished file is at most ~0.5 MB, so hashing it inline costs about a millisecond; use
     * [stopAsync] to keep even that off the main thread.
     */
    fun stop(): RecordedVoice? {
        val active: MediaRecorder?
        val file: File?
        val duration: Long
        synchronized(lock) {
            active = recorder
            file = target
            duration = if (startedAt > 0L) (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) else 0L
            recorder = null
            target = null
            startedAt = 0L
            smoothed = 0
            handler.removeCallbacks(autoStop)
        }
        if (active == null || file == null) return null

        var stopped = true
        try {
            active.stop()
        } catch (e: Exception) {
            stopped = false
        }
        runCatching { active.release() }

        if (!stopped || !file.isFile || file.length() <= 0L) {
            runCatching { file.delete() }
            return null
        }
        return RecordedVoice(
            filePath = file.absolutePath,
            durationMs = duration,
            size = file.length(),
            sha256 = MediaFiles.sha256(file)
        )
    }

    /** [stop] on [Dispatchers.IO], for callers that want the hashing off the main thread. */
    suspend fun stopAsync(): RecordedVoice? = withContext(Dispatchers.IO) { stop() }

    /** Throws the current take away and deletes its file. Safe to call when idle. */
    fun cancel() {
        val active: MediaRecorder?
        val file: File?
        synchronized(lock) {
            active = recorder
            file = target
            recorder = null
            target = null
            startedAt = 0L
            smoothed = 0
            handler.removeCallbacks(autoStop)
        }
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.release() }
        }
        file?.let { runCatching { it.delete() } }
    }

    /** Deletes a take the user chose not to send. */
    fun discard(take: RecordedVoice): Boolean = runCatching { File(take.filePath).delete() }.getOrDefault(false)

    /** Ends any take in progress and frees the recorder. Idempotent; safe from `onDispose`. */
    fun release() {
        onAutoStop = null
        cancel()
    }

    companion object {

        /** Anything shorter than this is a mis-tap rather than a message. */
        const val MIN_VOICE_MS = 1000L

        /** Hard ceiling for one voice message; the recorder stops itself here. */
        const val MAX_VOICE_MS = 60_000L

        /** 44.1 kHz keeps the encoder happy on every device while staying speech-legible. */
        const val SAMPLE_RATE = 44_100

        /** Mono 64 kbit/s ≈ 8 KB per second ≈ 480 KB per minute. */
        const val BIT_RATE = 64_000

        const val CHANNELS = 1

        /** AAC in MPEG-4; the engine turns any `audio/…` MIME into a voice message. */
        const val MIME = "audio/mp4"

        private const val DECAY_PERCENT = 60
        private const val MAX_RAW = 32_767
        private const val FLOOR_DB = -50.0

        /**
         * Maps the linear `getMaxAmplitude()` value (0..32767) onto a 0..100 bar using a
         * −50 dB…0 dB window, so ordinary speech is visible instead of hugging zero.
         */
        fun levelOf(raw: Int): Int {
            if (raw <= 0) return 0
            val db = 20.0 * log10(raw.coerceAtMost(MAX_RAW).toDouble() / MAX_RAW)
            val level = ((db - FLOOR_DB) / -FLOOR_DB * 100.0).toInt()
            return level.coerceIn(0, 100)
        }
    }
}

/**
 * A [VoiceRecorder] bound to the composition: the instance survives recomposition and any take
 * still running is discarded when the screen goes away.
 */
@Composable
fun rememberVoiceRecorder(): VoiceRecorder {
    val context = LocalContext.current.applicationContext
    val recorder = remember(context) { VoiceRecorder(context) }
    DisposableEffect(recorder) {
        onDispose { recorder.release() }
    }
    return recorder
}
