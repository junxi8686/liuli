package com.liuli.btchat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What [MediaMetadataRetriever] could tell us about a video.
 *
 * [width]/[height] are the *display* size: the container size with the rotation already applied,
 * which is what a player or an image bubble should use. The raw container size is kept because
 * the frame grab can come back either way round — see [VideoPipeline].
 */
data class VideoInfo(
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val containerWidth: Int,
    val containerHeight: Int
)

/**
 * Why a video import was refused: what came in, what the ceiling is, and a reason already worded
 * for the user. Produced by [VideoPipeline.lastRejection] so a caller that imported first can
 * still explain the refusal instead of showing a generic failure.
 */
data class VideoRejection(
    val sizeBytes: Long,
    val limitBytes: Long,
    val message: String
)

/**
 * Imports a video the user picked.
 *
 * There is no transcoder in the app, so the payload is copied **byte for byte** and hashed in the
 * same streaming pass; the only compression applied is to the preview frame. Because the original
 * size is what will cross the link, the payload is reported honestly and [MediaLimits.check]
 * decides what the UI should say about it.
 */
object VideoPipeline {

    /** Longest edge of the extracted preview frame. */
    val THUMB_EDGE: Int = Bitmaps.VIDEO_THUMB_MAX_EDGE

    /** JPEG quality of the preview frame. */
    const val THUMB_QUALITY = 72

    /** Below this duration a frame at 1/3 lands on the opening fade, so the first frame is used. */
    private const val MIN_DURATION_FOR_MID_FRAME_MS = 3_000L

    private const val FALLBACK_MIME = "video/mp4"

    /**
     * Copies [uri] into `files/media/` and extracts a preview frame.
     *
     * **A payload the app may not send is never copied.** Anything above
     * [MediaLimits.MAX_SEND_BYTES] is refused before a byte is written — and again right after the
     * copy when the provider published no size — because a copied-then-refused video would sit in
     * private storage with nothing left to delete it. When this returns `null` because of the
     * size, [lastRejection] carries the exact reason for the UI; a caller that wants to warn
     * *before* any work happens should call [probe] first and render [MediaLimits.check].
     *
     * @return the imported media, or `null` when the file is unreadable **or too large to send**.
     */
    suspend fun process(ctx: Context, uri: Uri): ImportedMedia? = withContext(Dispatchers.IO) {
        try {
            processBlocking(ctx, uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Why the most recent [process] refused a video, or `null` when nothing was refused.
     *
     * The vault contract returns `null` for "could not import", which cannot distinguish a broken
     * file from a file that is simply too big to put on this link. The UI reads this immediately
     * after a failed import to say which one it was.
     */
    @Volatile
    var lastRejection: VideoRejection? = null
        private set

    /**
     * Reads size, duration, display size and rotation without copying anything — lets the UI warn
     * about an oversized video *before* the app spends time and storage importing it.
     */
    fun probe(ctx: Context, uri: Uri): VideoInfo? =
        readInfo(MediaFiles.sizeOf(ctx, uri)) { it.setDataSource(ctx, uri) }

    private fun processBlocking(ctx: Context, uri: Uri): ImportedMedia? {
        // Clear first: the reason must describe *this* attempt, never the previous one — a UI that
        // reads it after any failed import would otherwise show a stale "too large" message.
        lastRejection = null

        val rawName = MediaFiles.displayName(ctx, uri) ?: uri.lastPathSegment
        val mime = MediaFiles.mimeOf(ctx, uri)
            ?: MediaFiles.mimeFromName(rawName).ifEmpty { FALLBACK_MIME }

        // A retriever that cannot produce width/height is looking at something that is not video.
        val info = probe(ctx, uri) ?: return null

        // Refuse before copying: those bytes are only worth writing if they may be sent.
        if (MediaLimits.exceedsLimit(info.sizeBytes)) {
            lastRejection = VideoRejection(
                sizeBytes = info.sizeBytes,
                limitBytes = MediaLimits.MAX_SEND_BYTES,
                message = MediaLimits.check(info.sizeBytes) ?: "视频超过可发送的体积上限"
            )
            return null
        }

        val transferId = newId()
        val name = MediaFiles.videoName(rawName, mime)
        val dest = MediaLayout.payloadFile(ctx, transferId, name)

        val payload = MediaFiles.copyIn(ctx, uri, dest) ?: return null
        if (MediaLimits.exceedsLimit(payload.size)) {
            // The provider reported nothing usable up front; now that the true size is known,
            // do not leave an unsendable blob behind.
            runCatching { dest.delete() }
            lastRejection = VideoRejection(
                sizeBytes = payload.size,
                limitBytes = MediaLimits.MAX_SEND_BYTES,
                message = MediaLimits.check(payload.size) ?: "视频超过可发送的体积上限"
            )
            return null
        }
        lastRejection = null

        val frame = extractFrame({ it.setDataSource(ctx, uri) }, info)
        val thumb = frame?.let { bitmap ->
            try {
                Bitmaps.buildThumbnail(
                    base = bitmap,
                    dest = MediaLayout.thumbFile(ctx, transferId),
                    maxEdge = THUMB_EDGE,
                    quality = THUMB_QUALITY
                )
            } finally {
                bitmap.recycle()
            }
        }

        return ImportedMedia(
            filePath = dest.absolutePath,
            thumbPath = thumb?.file?.absolutePath,
            thumbB64 = thumb?.b64,
            name = name,
            mime = mime,
            size = payload.size,
            width = info.width,
            height = info.height,
            durationMs = info.durationMs,
            sha256 = payload.sha256
        )
    }

    /**
     * Rebuilds a preview for a video that is already on disk, used when the sender's offer
     * carried no inline thumbnail.
     */
    internal fun thumbnail(ctx: Context, source: File, transferId: String): File? {
        val info = try {
            readInfo(source.length()) { it.setDataSource(source.absolutePath) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        val frame = extractFrame({ it.setDataSource(source.absolutePath) }, info) ?: return null
        return try {
            Bitmaps.buildThumbnail(
                base = frame,
                dest = MediaLayout.thumbFile(ctx, transferId),
                maxEdge = THUMB_EDGE,
                quality = THUMB_QUALITY
            ).file
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            frame.recycle()
        }
    }

    /**
     * Opens a retriever with [setData], reads the metadata, and always releases it. `null` means
     * "not a video we can handle" rather than "an error to report".
     */
    private fun readInfo(sizeBytes: Long, setData: (MediaMetadataRetriever) -> Unit): VideoInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            setData(retriever)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val containerWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val containerHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (containerWidth <= 0 || containerHeight <= 0) return null

            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val swapped = rotation == 90 || rotation == 270

            VideoInfo(
                sizeBytes = sizeBytes,
                durationMs = durationMs.coerceAtLeast(0L),
                width = if (swapped) containerHeight else containerWidth,
                height = if (swapped) containerWidth else containerHeight,
                rotationDegrees = rotation,
                containerWidth = containerWidth,
                containerHeight = containerHeight
            )
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Grabs a representative frame — a third of the way in, which skips the black opening frame
     * and usually shows something recognisable.
     */
    private fun extractFrame(setData: (MediaMetadataRetriever) -> Unit, info: VideoInfo): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setData(retriever)
            val atUs = if (info.durationMs > MIN_DURATION_FOR_MID_FRAME_MS) info.durationMs * 1000L / 3 else 0L
            val frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { orientFrame(it, info) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * `MediaMetadataRetriever` bakes the rotation into the grabbed frame on most builds but not
     * on all of them, and the container size it reports is always unrotated. Compare the frame
     * shape against both to find out which one we got, and rotate only a genuinely raw frame.
     */
    private fun orientFrame(frame: Bitmap, info: VideoInfo): Bitmap {
        if (info.rotationDegrees != 90 && info.rotationDegrees != 270) return frame

        val looksRaw = frame.width == info.containerWidth && frame.height == info.containerHeight
        val looksRotated = frame.width == info.containerHeight && frame.height == info.containerWidth
        if (!looksRaw || looksRotated) return frame

        val matrix = Matrix().apply { postRotate(info.rotationDegrees.toFloat()) }
        return try {
            val out = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
            if (out !== frame) frame.recycle()
            out
        } catch (e: OutOfMemoryError) {
            frame
        }
    }
}
