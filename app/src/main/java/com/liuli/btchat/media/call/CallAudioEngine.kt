package com.liuli.btchat.media.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process

/**
 * Microphone capture and speaker playback for a call.
 *
 * Geometry: **16 kHz, mono, PCM-16, 20 ms per frame = 640 bytes = 32 KB/s** one way. Two-way
 * audio therefore costs ~64 KB/s of a link measured at 100–200 KB/s, which is why nothing here
 * spends a byte more than it must.
 *
 * * Capture uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION], so the platform's echo canceller
 *   and noise suppressor are in the path — without AEC a call on speaker would howl.
 * * Playback is a streaming [AudioTrack] whose `write()` is the clock: one 640-byte frame blocks
 *   for ~20 ms, which paces the loop without a timer to drift.
 * * **Muting stops the frames entirely** rather than sending silence. The peer's jitter buffer
 *   already turns an empty queue into silence by design (and it is unit-tested), so spending
 *   32 KB/s of the scarcest resource in this app on zeros buys nothing; the mute state travels
 *   separately in `CallStatePacket`.
 *
 * Threading: two dedicated threads, `liuli-call-mic` and `liuli-call-speaker`, both at
 * `THREAD_PRIORITY_URGENT_AUDIO`. Nothing here ever runs on the main thread.
 */
internal class CallAudioEngine(private val ctx: Context) {

    /**
     * Mic capture callback, invoked on the capture thread with a reused 640-byte buffer that is
     * valid only inside the call — the transport copies what it keeps.
     */
    @Volatile
    var onFrame: ((frame: ByteArray, length: Int) -> Unit)? = null

    /** When `true`, no audio frames leave the device (see the class KDoc). */
    @Volatile
    var muted = false

    /**
     * What went wrong with the audio devices, or `null` while they are healthy.
     *
     * A dead microphone or speaker used to end its loop silently, leaving a one-way call that
     * looked perfectly normal on both sides. Anything that stops a loop reports here instead.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** Called from the audio threads when a device fails; the media layer publishes it at once. */
    @Volatile
    var onError: ((message: String) -> Unit)? = null

    /** 0..100 peak level of the last captured frame, for a speaking indicator. */
    @Volatile
    var micLevel = 0
        private set

    @Volatile
    var framesSent = 0L
        private set

    @Volatile
    var framesReceived = 0L
        private set

    private val jitter = AudioJitterBuffer()

    val droppedFrames: Long get() = jitter.droppedFrames
    val underruns: Long get() = jitter.underruns
    val silenceFrames: Long get() = jitter.silenceFrames
    val depthMs: Int get() = jitter.depthMs

    @Volatile
    private var running = false

    /** `true` while audio focus is held by someone else: we keep quiet instead of fighting. */
    @Volatile
    private var focusLost = false

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var micThread: Thread? = null
    private var playThread: Thread? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    val isRunning: Boolean get() = running

    /**
     * Opens the microphone and speaker and starts both loops.
     *
     * @return `false` when `RECORD_AUDIO` is missing, another app owns the microphone, or either
     *   device refuses to initialise. Never throws.
     */
    fun start(): Boolean {
        if (running) return false
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val minRecord = AudioRecord.getMinBufferSize(
            CallAudioMath.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minTrack = AudioTrack.getMinBufferSize(
            CallAudioMath.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minRecord <= 0 || minTrack <= 0) return false

        // ~80 ms of capture buffer: enough that a scheduling hiccup does not drop a frame.
        val createdRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CallAudioMath.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRecord, CallAudioMath.FRAME_BYTES * 4)
            )
        } catch (e: Exception) {
            null
        }
        if (createdRecord == null || createdRecord.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { createdRecord?.release() }
            return false
        }

        // ~60 ms of playback buffer: small enough to keep latency conversational.
        val createdTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CallAudioMath.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minTrack, CallAudioMath.FRAME_BYTES * 3))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            null
        }
        if (createdTrack == null || createdTrack.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { createdTrack?.release() }
            runCatching { createdRecord.release() }
            return false
        }

        jitter.reset()
        framesSent = 0L
        framesReceived = 0L
        micLevel = 0
        muted = false
        lastError = null

        record = createdRecord
        track = createdTrack
        requestAudioFocus()

        try {
            createdRecord.startRecording()
            createdTrack.play()
        } catch (e: Exception) {
            runCatching { createdRecord.release() }
            runCatching { createdTrack.release() }
            record = null
            track = null
            abandonAudioFocus()
            return false
        }

        running = true

        micThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            captureLoop(createdRecord)
        }, "liuli-call-mic").apply { start() }

        playThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            playbackLoop(createdTrack)
        }, "liuli-call-speaker").apply { start() }

        return true
    }

    /**
     * Stops both loops and releases the devices. Idempotent; blocks briefly (≤0.5 s per thread)
     * until no callback can fire again.
     */
    fun stop() {
        val hadRecord = record
        val hadTrack = track
        running = false

        // Unblock the two blocking calls so the loops notice `running` immediately.
        runCatching { hadRecord?.stop() }
        runCatching { hadTrack?.stop() }

        micThread?.let { runCatching { it.join(500) } }
        playThread?.let { runCatching { it.join(500) } }
        micThread = null
        playThread = null

        runCatching { hadRecord?.release() }
        runCatching { hadTrack?.release() }
        record = null
        track = null

        abandonAudioFocus()
        jitter.clear()
        micLevel = 0
        // Counters are deliberately kept: the last stats snapshot reports what the call really did.
    }

    /** Queues a frame from the peer; safe from any thread, copies what it keeps. */
    fun submit(data: ByteArray, offset: Int, length: Int) {
        jitter.push(data, offset, length)
        framesReceived++
    }

    // ------------------------------------------------------------------ loops

    private fun captureLoop(active: AudioRecord) {
        val frame = ByteArray(CallAudioMath.FRAME_BYTES)
        while (running) {
            val read = try {
                active.read(frame, 0, frame.size)
            } catch (e: Exception) {
                -1
            }
            if (read < 0) {
                // Only an error when we did not ask to stop: `stop()` unblocks the read on purpose.
                if (running) fail("麦克风读取失败（$read）")
                break
            }
            if (read < frame.size) continue

            if (muted) {
                micLevel = 0
                continue
            }
            micLevel = CallAudioMath.levelOf16(frame)
            framesSent++
            // The transport copies inside this call, so the buffer is reused every iteration.
            onFrame?.invoke(frame, frame.size)
        }
        micLevel = 0
    }

    private fun playbackLoop(active: AudioTrack) {
        val silence = CallAudioMath.silenceFrame()
        while (running) {
            val frame = jitter.pullOrSilence()
            // While focus is lost we still drain the queue (no growing delay) but play nothing.
            val outgoing = if (focusLost) silence else frame
            val written = try {
                active.write(outgoing, 0, outgoing.size)
            } catch (e: Exception) {
                if (running) fail("扬声器写入异常：${e.javaClass.simpleName}")
                break
            }
            if (written < 0) {
                if (running) fail("扬声器写入失败（$written）")
                break
            }
        }
    }

    /**
     * Ends the whole engine because a device failed, and says so.
     *
     * Both loops watch [running], so a broken device stops the call's audio instead of leaving
     * half of it spinning against a dead handle. The message travels to the media layer, which
     * puts it into `CallStats.lastError` the moment it arrives.
     */
    private fun fail(message: String) {
        if (lastError == null) lastError = message
        running = false
        onError?.invoke(message)
    }

    // ------------------------------------------------------------ audio focus

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        runCatching {
            val manager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@runCatching
            audioManager = manager
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change ->
                    // Graceful degradation: fall silent, keep the call (and its signalling) alive.
                    focusLost = change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                }
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        runCatching {
            val manager = audioManager ?: return@runCatching
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = AudioManager.MODE_NORMAL
        }
        focusRequest = null
        audioManager = null
        focusLost = false
    }
}
