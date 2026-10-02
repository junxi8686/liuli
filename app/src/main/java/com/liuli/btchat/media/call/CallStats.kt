package com.liuli.btchat.media.call

import android.graphics.Bitmap
import com.liuli.btchat.bt.call.CallMedia
import kotlinx.coroutines.flow.StateFlow

/**
 * Live counters for a call, republished by the media layer a couple of times per second.
 *
 * The Bluetooth link is the bottleneck (100–200 KB/s measured end to end), so these are the
 * numbers that decide what the UI may honestly claim. They are all *measured*: frame sizes and
 * frame rate are taken from what was actually produced and sent, never from a target.
 */
data class CallStats(
    /** `true` while the media engine is running a call. */
    val active: Boolean = false,

    /** Why a medium is missing (permission, busy device …), for an honest UI notice. */
    val lastError: String? = null,

    /** 20 ms audio frames handed to the transport. */
    val audioFramesSent: Long = 0,
    val audioFramesReceived: Long = 0,

    /** Silence the player had to invent because the jitter buffer was empty. */
    val audioSilenceFrames: Long = 0,

    /** Empty polls *after* the buffer had primed — the real "we are starving" signal. */
    val audioUnderruns: Long = 0,

    /** Frames the jitter buffer threw away because it was full (always the oldest). */
    val audioDropped: Long = 0,

    /** Current buffer depth; the target band is 60–120 ms. */
    val jitterDepthMs: Int = 0,

    val videoFramesSent: Long = 0,
    val videoFramesReceived: Long = 0,
    val videoBytesSent: Long = 0,

    /** Measured over the last completed one-second window. */
    val videoFps: Float = 0f,
    val videoAverageFrameBytes: Int = 0,
    val videoLastFrameBytes: Int = 0,

    /** Camera frames the pipeline deliberately skipped: fps cap, or impossible to fit the budget. */
    val videoFramesSkipped: Long = 0,
    val cameraErrors: Long = 0,

    /** Current microphone level 0..100, for a speaking indicator. */
    val micLevel: Int = 0
) {
    /**
     * Honest label for a video call, derived from the *measured* rate — `null` when the picture is
     * good enough not to need one. The UI should show this instead of promising smoothness.
     */
    val videoQualityNote: String?
        get() = when {
            !active -> null
            videoFramesSent == 0L -> "摄像头未开启"
            videoFps <= 0f -> null
            videoFps < 4f -> "低帧率画面（约 ${"%.1f".format(videoFps)} fps）"
            videoFps < 6f -> "低帧率画面（约 ${"%.0f".format(videoFps)} fps）"
            else -> null
        }
}

/**
 * The part of the call media that only the UI cares about.
 *
 * The transport contract ([CallMedia]) stays exactly as `bt/call` defined it; this interface is
 * the media layer's own, additional surface, so the call screen can render the peer's picture and
 * tell the truth about the measured bitrate without the transport knowing about bitmaps or stats.
 */
interface CallMediaDiagnostics {

    /** Measured counters; see [CallStats]. */
    val stats: StateFlow<CallStats>

    /** Newest decoded frame from the peer, or `null` before the first one arrives. */
    val remoteVideo: StateFlow<Bitmap?>
}

/**
 * The diagnostics of whatever media the engine currently holds, or `null` when there is none —
 * lets the UI write `Engine.callMedia.diagnostics()?.remoteVideo` without a raw cast.
 */
fun CallMedia?.diagnostics(): CallMediaDiagnostics? = this as? CallMediaDiagnostics
