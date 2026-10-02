package com.liuli.btchat.media.call

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.liuli.btchat.media.Bitmaps
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Outgoing video, squeezed into a Bluetooth-sized hole.
 *
 * The budget, chosen because voice already owns ~64 KB/s of a 100–200 KB/s link:
 * **320×240 @ ≤8 fps, JPEG quality 50, ≤ 8 KB per frame** (≈40–64 KB/s). Every frame is measured
 * after encoding; anything that would not fit the budget is re-encoded smaller rather than sent,
 * and the counters in [snapshot] report what really happened — this pipeline is not allowed to
 * claim a smoothness the link cannot carry.
 *
 * Pipeline: `ImageReader(YUV_420_888)` → NV21 (pure, unit-tested index maths) →
 * `YuvImage.compressToJpeg(q50)` → size gate → [onFrame]. All of it runs on a dedicated
 * `liuli-call-camera` thread, so a slow encode can never block a camera callback into an ANR —
 * a skipped frame is fine, a stalled camera is not.
 *
 * Incoming frames are decoded to `RGB_565` on a separate `liuli-call-video-decode` thread, one
 * frame in flight at a time (newer frames replace the attempt, never queue up).
 *
 * The camera is only used while a call is running: [stop] closes the device, the session and the
 * reader, and is safe to call repeatedly — including right after a camera switch.
 */
internal class CallVideoEngine(
    private val ctx: Context,
    private val onFrame: (jpeg: ByteArray) -> Unit,
    private val onRemoteFrame: (bitmap: Bitmap) -> Unit,
    private val onError: (message: String) -> Unit
) {

    /** Measured video numbers, read by the stats publisher. */
    class VideoSnapshot(
        val framesSent: Long,
        val framesReceived: Long,
        val bytesSent: Long,
        val fps: Float,
        val averageBytes: Int,
        val lastBytes: Int,
        val throttled: Long,
        val oversized: Long,
        val errors: Long,
        val cameraOn: Boolean,
        val frontFacing: Boolean
    )

    @Volatile
    private var enabled = false

    @Volatile
    private var frontFacing = true

    @Volatile
    private var framesSent = 0L

    @Volatile
    private var framesReceived = 0L

    @Volatile
    private var bytesSent = 0L

    @Volatile
    private var throttledFrames = 0L

    @Volatile
    private var oversizedFrames = 0L

    @Volatile
    private var errorCount = 0L

    @Volatile
    private var lastFrameBytes = 0

    @Volatile
    private var measuredFps = 0f

    @Volatile
    private var measuredAverageBytes = 0

    @Volatile
    private var cameraOpen = false

    private var windowStart = 0L
    private var windowFrames = 0
    private var windowBytes = 0L

    private var started = false
    private var cameraThread: HandlerThread? = null
    private var decodeThread: HandlerThread? = null
    private var handler: Handler? = null
    private var decodeHandler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var frameWidth = WIDTH
    private var frameHeight = HEIGHT
    private var fpsRange: android.util.Range<Int>? = null
    private var afMode: Int? = null

    private val decoding = AtomicBoolean(false)
    private var lastSentAt = 0L

    /** Everything camera-related runs on the camera thread, never on the caller's. */
    private val cameraExecutor: Executor = Executor { command -> handler?.post(command) }

    /**
     * Bumped on every open **and** every teardown, and captured by each opening's callbacks.
     *
     * `cameraDevice != null` cannot tell "the device I am opening right now" from "the device I
     * abandoned 300 ms ago": opening a camera takes 100 ms–2 s, so flipping before `onOpened`
     * arrives used to adopt the *old* device and bind it to the reader that had just been closed —
     * outgoing video then stayed dead until the user flipped again. The generation makes a stale
     * callback recognisable, so it is closed instead of adopted.
     */
    private var cameraGeneration = 0

    /** Starts the camera (front by default, as any chat app does) and the decode thread. */
    fun start(front: Boolean) {
        if (started) return
        started = true
        frontFacing = front
        enabled = true

        val camera = HandlerThread("liuli-call-camera").apply { start() }
        cameraThread = camera
        handler = Handler(camera.looper)

        val decoder = HandlerThread("liuli-call-video-decode").apply { start() }
        decodeThread = decoder
        decodeHandler = Handler(decoder.looper)

        handler?.post { openInternal() }
    }

    /** Closes the camera and both threads. Idempotent. */
    fun stop() {
        enabled = false
        started = false

        handler?.post { closeSessionInternal() }
        cameraThread?.quitSafely()
        cameraThread?.let { runCatching { it.join(1_000) } }
        cameraThread = null
        handler = null

        decodeHandler = null
        decodeThread?.quitSafely()
        decodeThread?.let { runCatching { it.join(1_000) } }
        decodeThread = null
        decoding.set(false)
    }

    /** Turns outgoing video on or off without touching audio. */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!started) return
        handler?.post {
            if (on) {
                if (cameraDevice == null && imageReader == null) openInternal()
            } else {
                closeSessionInternal()
            }
        }
    }

    /** Flips between the front and the back camera by restarting the session. */
    fun switchCamera() {
        if (!started) return
        handler?.post {
            frontFacing = !frontFacing
            closeSessionInternal()
            if (enabled) openInternal()
        }
    }

    /** Current camera facing, for the UI's mirror/side indicator. */
    val isFrontFacing: Boolean get() = frontFacing

    fun snapshot(): VideoSnapshot = VideoSnapshot(
        framesSent = framesSent,
        framesReceived = framesReceived,
        bytesSent = bytesSent,
        fps = measuredFps,
        averageBytes = measuredAverageBytes,
        lastBytes = lastFrameBytes,
        throttled = throttledFrames,
        oversized = oversizedFrames,
        errors = errorCount,
        cameraOn = cameraOpen,
        frontFacing = frontFacing
    )

    /**
     * Decodes a frame from the peer for display.
     *
     * Oversized payloads are rejected before allocating anything, and only one decode is in flight
     * at a time: a picture that cannot keep up with the sender is replaced, never queued.
     */
    fun submitRemoteJpeg(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || length > MAX_REMOTE_BYTES) return
        val start = offset.coerceIn(0, data.size)
        val end = (start + length).coerceAtMost(data.size)
        if (end <= start) return

        val target = decodeHandler ?: return
        if (!decoding.compareAndSet(false, true)) return
        // Copying can fail on a huge burst; the flag must not stay set, or every later frame of
        // this call would be dropped without a trace.
        val copy = try {
            data.copyOfRange(start, end)
        } catch (e: Exception) {
            decoding.set(false)
            return
        } catch (e: OutOfMemoryError) {
            decoding.set(false)
            return
        }

        target.post {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(copy, 0, copy.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                    bounds.outWidth > MAX_REMOTE_EDGE || bounds.outHeight > MAX_REMOTE_EDGE
                ) {
                    return@post
                }
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val bitmap = BitmapFactory.decodeByteArray(copy, 0, copy.size, options)
                if (bitmap != null) {
                    framesReceived++
                    onRemoteFrame(bitmap)
                }
            } catch (e: Exception) {
                // A broken frame is dropped silently: the next one arrives in 125 ms.
            } finally {
                decoding.set(false)
            }
        }
    }

    // ------------------------------------------------------------- camera side

    private fun openInternal() {
        if (!enabled) return
        closeSessionInternal()

        val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            fail("没有相机服务")
            return
        }
        val cameraId = pickCameraId(manager, frontFacing)
        if (cameraId == null) {
            fail("没有可用摄像头")
            return
        }
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()

        val size = pickSize(characteristics)
        frameWidth = size.first
        frameHeight = size.second
        fpsRange = pickFpsRange(characteristics)
        afMode = pickAfMode(characteristics)

        val reader = try {
            ImageReader.newInstance(frameWidth, frameHeight, ImageFormat.YUV_420_888, 2)
        } catch (e: Exception) {
            fail("相机初始化失败")
            return
        }
        reader.setOnImageAvailableListener({ source -> handleImage(source) }, handler)
        imageReader = reader

        // Every callback below belongs to this opening only; a later flip or stop invalidates it.
        val generation = ++cameraGeneration

        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        // Arrived after a flip or a stop: this device belongs to a dead request.
                        if (generation != cameraGeneration || !enabled) {
                            runCatching { device.close() }
                            return
                        }
                        cameraDevice = device
                        cameraOpen = true
                        createSession(device, reader, generation)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        runCatching { device.close() }
                        if (generation != cameraGeneration) return
                        if (cameraDevice === device) cameraDevice = null
                        cameraOpen = false
                        errorCount++
                        onError("摄像头已断开")
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        runCatching { device.close() }
                        if (generation != cameraGeneration) return
                        if (cameraDevice === device) cameraDevice = null
                        cameraOpen = false
                        errorCount++
                        onError("摄像头错误（$error）")
                    }
                },
                handler
            )
        } catch (e: SecurityException) {
            closeSessionInternal() // releases the reader this attempt created
            fail("没有相机权限")
        } catch (e: Exception) {
            closeSessionInternal()
            fail("无法打开摄像头")
        }
    }

    private fun createSession(device: CameraDevice, reader: ImageReader, generation: Int) {
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(reader.surface)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            afMode?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) }
            fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            val request = builder.build()

            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    // A flip may have replaced the device while this session was configuring;
                    // adopting it would leave the live preview pointed at a closed camera.
                    if (generation != cameraGeneration || cameraDevice !== device || !enabled) {
                        runCatching { session.close() }
                        return
                    }
                    captureSession = session
                    runCatching { session.setRepeatingRequest(request, null, handler) }
                        .onFailure {
                            errorCount++
                            onError("无法启动相机预览")
                        }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (generation != cameraGeneration) return
                    errorCount++
                    onError("相机会话配置失败")
                }
            }

            // SessionConfiguration is the current API; the List<Surface> overload is deprecated.
            device.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    cameraExecutor,
                    callback
                )
            )
        } catch (e: Exception) {
            errorCount++
            onError("相机会话创建失败")
        }
    }

    /**
     * Closes session, device and reader, and invalidates every callback still in flight.
     * Camera-thread only, and safe to call when already closed.
     */
    private fun closeSessionInternal() {
        cameraGeneration++ // any open/session callback from before this point is now stale
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        runCatching { imageReader?.close() }
        imageReader = null
        cameraOpen = false
    }

    private fun handleImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return

        try {
            if (!enabled) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastSentAt < MIN_FRAME_INTERVAL_MS) {
                throttledFrames++
                return
            }
            val jpeg = encode(image)
            if (jpeg == null) {
                oversizedFrames++
                return
            }
            lastSentAt = now
            framesSent++
            bytesSent += jpeg.size
            lastFrameBytes = jpeg.size
            recordWindow(jpeg.size, now)
            onFrame(jpeg)
        } catch (e: Exception) {
            errorCount++
            onError("视频编码失败")
        } finally {
            image.close()
        }
    }

    /** YUV_420_888 → NV21 → JPEG q50, then down to the frame budget when needed. */
    private fun encode(image: Image): ByteArray? {
        val planes = image.planes
        if (planes.size < 3) return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val nv21 = CallAudioMath.yuv420ToNv21(
            y = yPlane.buffer.toByteArray(),
            yRowStride = yPlane.rowStride,
            u = uPlane.buffer.toByteArray(),
            v = vPlane.buffer.toByteArray(),
            uvRowStride = uPlane.rowStride,
            uvPixelStride = uPlane.pixelStride,
            width = width,
            height = height
        )

        val out = ByteArrayOutputStream(INITIAL_JPEG_BUFFER)
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        val jpeg = out.toByteArray()
        if (jpeg.isEmpty()) return null
        return if (jpeg.size <= MAX_FRAME_BYTES) jpeg else shrink(jpeg)
    }

    /**
     * Last resort when a frame does not fit the budget: re-encode it smaller. Skipping the frame
     * would be cheaper, but a slightly softer picture beats a hole in the video.
     */
    private fun shrink(jpeg: ByteArray): ByteArray? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            var quality = SHRINK_START_QUALITY
            var scale = 1f
            repeat(SHRINK_STEPS) {
                val edge = (maxOf(bitmap.width, bitmap.height) * scale).toInt().coerceAtLeast(MIN_SHRINK_EDGE)
                val frame = if (scale < 1f) Bitmaps.downscale(bitmap, edge) else bitmap
                val bytes = Bitmaps.encodeJpeg(frame, quality)
                if (frame !== bitmap) frame.recycle()
                if (bytes.isNotEmpty() && bytes.size <= MAX_FRAME_BYTES) return bytes
                quality -= 10
                scale *= 0.8f
            }
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Measured fps and average frame size over one-second windows. */
    private fun recordWindow(bytes: Int, now: Long) {
        if (windowStart == 0L) windowStart = now
        windowFrames++
        windowBytes += bytes
        val elapsed = now - windowStart
        if (elapsed >= 1_000L) {
            measuredFps = windowFrames * 1000f / elapsed
            measuredAverageBytes = (windowBytes / maxOf(1, windowFrames)).toInt()
            windowStart = now
            windowFrames = 0
            windowBytes = 0L
        }
    }

    private fun fail(message: String) {
        errorCount++
        onError(message)
    }

    private fun pickCameraId(manager: CameraManager, front: Boolean): String? {
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val facing = runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()
            if (facing == wanted) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    /** 320×240 when the device offers it, otherwise the smallest supported size that is close. */
    private fun pickSize(characteristics: CameraCharacteristics?): Pair<Int, Int> {
        val sizes = runCatching {
            characteristics
                ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
        }.getOrNull()
        if (sizes.isNullOrEmpty()) return WIDTH to HEIGHT
        sizes.firstOrNull { it.width == WIDTH && it.height == HEIGHT }?.let { return it.width to it.height }
        val candidate = sizes.filter { it.width <= 640 && it.height <= 480 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
        return candidate?.let { it.width to it.height } ?: (WIDTH to HEIGHT)
    }

    /** Prefers a low fps range near the target; falls back to whatever the device advertises. */
    private fun pickFpsRange(characteristics: CameraCharacteristics?): android.util.Range<Int>? {
        val ranges = runCatching {
            characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        }.getOrNull() ?: return null
        if (ranges.isEmpty()) return null
        val low = ranges.filter { it.upper <= 10 }
        return low.minByOrNull { abs(it.upper - TARGET_FPS) }
            ?: ranges.filter { it.upper <= 15 }.minByOrNull { it.upper }
            ?: ranges.minByOrNull { it.upper }
    }

    private fun pickAfMode(characteristics: CameraCharacteristics?): Int? {
        val modes = runCatching {
            characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        }.getOrNull() ?: return null
        return when {
            modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> null
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val bytes = ByteArray(remaining())
        get(bytes)
        return bytes
    }

    companion object {
        /** Target frame size: small enough that a face is still recognisable at 320×240. */
        const val WIDTH = 320
        const val HEIGHT = 240

        const val JPEG_QUALITY = 50

        /** Hard per-frame budget. Anything larger does not get sent. */
        const val MAX_FRAME_BYTES = 8 * 1024

        /** Ceiling of 8 fps; the camera may well deliver fewer and that is reported, not hidden. */
        const val TARGET_FPS = 8
        const val MIN_FRAME_INTERVAL_MS = 1_000L / TARGET_FPS

        private const val INITIAL_JPEG_BUFFER = 16 * 1024
        private const val SHRINK_START_QUALITY = 40
        private const val SHRINK_STEPS = 4
        private const val MIN_SHRINK_EDGE = 120

        /** A peer frame larger than this is not a video frame; refuse before allocating. */
        const val MAX_REMOTE_BYTES = 256 * 1024
        const val MAX_REMOTE_EDGE = 1_920
    }
}
