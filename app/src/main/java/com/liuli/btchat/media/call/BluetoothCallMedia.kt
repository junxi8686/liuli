package com.liuli.btchat.media.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import com.liuli.btchat.bt.call.CallMedia
import com.liuli.btchat.core.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The real call media: microphone, speaker and camera for one Bluetooth call at a time.
 *
 * It implements the transport's [CallMedia] contract (frame in, frame out) and adds the extra
 * surface the call screen needs — [stats] and [remoteVideo] — through [CallMediaDiagnostics], so
 * the shared transport contract stays free of bitmaps and diagnostics.
 *
 * Assembly is one line, owned by the DI layer:
 * ```kotlin
 * Engine.callMedia = BluetoothCallMedia(applicationContext)
 * ```
 *
 * Budgets, all chosen against a measured 100–200 KB/s RFCOMM link:
 * * audio **16 kHz mono PCM-16, 20 ms / 640 B per frame, ~32 KB/s each way**;
 * * video **320×240, ≤8 fps, JPEG q50, ≤8 KB per frame (~40–64 KB/s)**.
 *
 * Degradation is honest rather than fatal: with no `RECORD_AUDIO` the call still connects and
 * [lastError] says why there is no sound; with no `CAMERA` a video call silently becomes a voice
 * call and says so; muting simply stops the frames. Nothing in here throws at its caller.
 */
class BluetoothCallMedia(context: Context) : CallMedia, CallMediaDiagnostics {

    private val ctx: Context = context.applicationContext

    private val audio = CallAudioEngine(ctx)

    private val videoEngine = CallVideoEngine(
        ctx = ctx,
        onFrame = { jpeg -> onFrame?.invoke(Wire.CALL_VIDEO, jpeg, jpeg.size) },
        onRemoteFrame = { bitmap -> remoteVideoFlow.value = bitmap },
        // Asynchronous camera failures (open, session, disconnect) are published the moment they
        // happen: a UI that only looked at `lastError` once, at `start()`, would never see them.
        onError = { message -> reportError(message) }
    )

    init {
        // Same for the audio devices: a dying microphone must reach the UI, not just end a loop.
        audio.onError = { message -> reportError(message) }
    }

    private val statsFlow = MutableStateFlow(CallStats())
    override val stats: StateFlow<CallStats> = statsFlow.asStateFlow()

    private val remoteVideoFlow = MutableStateFlow<Bitmap?>(null)
    override val remoteVideo: StateFlow<Bitmap?> = remoteVideoFlow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    override var onFrame: ((kind: Int, data: ByteArray, len: Int) -> Unit)? = null

    @Volatile
    private var error: String? = null

    @Volatile
    private var running = false

    @Volatile
    private var videoCall = false

    @Volatile
    private var cameraEnabled = false

    private var callId: String? = null

    override val lastError: String? get() = error

    override val active: Boolean get() = running

    /** `true` when the outgoing camera is currently sending. */
    val cameraOn: Boolean get() = cameraEnabled

    /** Which camera is live, for the flip button's state. */
    val frontCamera: Boolean get() = videoEngine.isFrontFacing

    /**
     * Starts capture and playback for [callId].
     *
     * Missing permissions are reported through [lastError] instead of throwing, and whatever can
     * run does run — a call without a microphone is still a call.
     */
    override fun start(callId: String, video: Boolean) {
        stop() // never stack two calls on one engine

        this.callId = callId
        videoCall = video
        error = null

        audio.onFrame = { frame, length -> onFrame?.invoke(Wire.CALL_AUDIO, frame, length) }
        val audioStarted = audio.start()
        if (!audioStarted) {
            error = if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                "没有麦克风权限，通话已接通但没有声音"
            } else {
                "麦克风被占用，通话已接通但没有声音"
            }
        }

        var videoStarted = false
        if (video) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                videoEngine.start(front = true)
                cameraEnabled = true
                videoStarted = true
            } else {
                error = join(error, "没有相机权限，已切换为语音通话")
            }
        }

        running = audioStarted || videoStarted
        if (running) startTicker() else publish()
    }

    /** Stops everything. Idempotent, and guarantees no [onFrame] callback after it returns. */
    override fun stop() {
        videoEngine.stop()
        audio.stop()
        ticker?.cancel()
        ticker = null
        running = false
        cameraEnabled = false
        remoteVideoFlow.value = null
        publish()
    }

    /** Muting stops the audio frames; the peer's jitter buffer turns that into silence by design. */
    override fun setMuted(muted: Boolean) {
        audio.muted = muted
        publish()
    }

    /** No-op outside a video call, as the transport contract requires. */
    override fun setCamera(on: Boolean) {
        if (!videoCall) return
        cameraEnabled = on
        videoEngine.setEnabled(on)
        publish()
    }

    /** No-op outside a video call. */
    override fun switchCamera() {
        if (!videoCall) return
        videoEngine.switchCamera()
        publish()
    }

    override fun onRemoteFrame(kind: Int, data: ByteArray, offset: Int, len: Int) {
        when (kind) {
            Wire.CALL_AUDIO -> audio.submit(data, offset, len)
            Wire.CALL_VIDEO -> videoEngine.submitRemoteJpeg(data, offset, len)
            else -> Unit // unknown media kind: ignore, stay forward compatible
        }
    }

    /** Frees the diagnostics scope as well; call when the engine is torn down for good. */
    fun release() {
        stop()
        runCatching { scope.cancel() }
    }

    // --------------------------------------------------------------- internals

    /**
     * Records a failure reported by one of the engines and republishes at once.
     *
     * Called from the camera and audio threads; [publish] only reads volatile counters, so it is
     * safe from any of them. Publishing immediately is the point: a `lastError` that only appears
     * on the next 500 ms tick is a `lastError` a one-shot reader can miss.
     */
    private fun reportError(message: String) {
        error = message
        publish()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && running) {
                publish()
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun publish() {
        val video = videoEngine.snapshot()
        statsFlow.value = CallStats(
            active = running,
            lastError = error,
            audioFramesSent = audio.framesSent,
            audioFramesReceived = audio.framesReceived,
            audioSilenceFrames = audio.silenceFrames,
            audioUnderruns = audio.underruns,
            audioDropped = audio.droppedFrames,
            jitterDepthMs = audio.depthMs,
            videoFramesSent = video.framesSent,
            videoFramesReceived = video.framesReceived,
            videoBytesSent = video.bytesSent,
            videoFps = video.fps,
            videoAverageFrameBytes = video.averageBytes,
            videoLastFrameBytes = video.lastBytes,
            videoFramesSkipped = video.throttled + video.oversized,
            cameraErrors = video.errors,
            micLevel = audio.micLevel
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun join(first: String?, second: String): String =
        if (first.isNullOrBlank()) second else "$first；$second"

    private companion object {
        /** Fast enough for a live level/fps readout, slow enough to stay off the audio thread. */
        const val STATS_INTERVAL_MS = 500L
    }
}
