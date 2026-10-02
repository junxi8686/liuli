package com.liuli.btchat.media

import android.content.Context
import android.net.Uri
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Imports a picture the user picked into app storage, in the shape the chat needs.
 *
 * A phone photo is 3–8 MB, which is 20–60 seconds on a 150 KB/s RFCOMM link, so nothing is ever
 * sent as-is:
 *  * the payload is re-encoded to JPEG, long edge ≤ 1600 px, quality 80 — typically 250–500 KB;
 *  * a 360 px / quality 75 thumbnail is written to `files/thumbs/<transferId>.jpg`;
 *  * a Base64 copy of that thumbnail, guaranteed to be at most 12 KiB of text, is returned in
 *    [ImportedMedia.thumbB64] so `FILE_OFFER` can carry a preview before the payload arrives.
 *
 * All decoding happens off the main thread and every failure (corrupt file, undecodable format,
 * out-of-memory on a huge panorama) yields `null` instead of an exception.
 */
object ImagePipeline {

    /** Longest edge of the JPEG that is sent. */
    val MAX_EDGE: Int = Bitmaps.IMAGE_MAX_EDGE

    /** Longest edge of the on-disk bubble thumbnail. */
    val THUMB_EDGE: Int = Bitmaps.THUMB_MAX_EDGE

    /** JPEG quality of the payload. */
    const val JPEG_QUALITY = 80

    /** JPEG quality of the on-disk thumbnail. */
    const val THUMB_QUALITY = 75

    /** Every imported picture is a JPEG, whatever the user picked. */
    const val MIME = "image/jpeg"

    /**
     * Compresses [uri] into `files/media/` and produces its thumbnail.
     *
     * @return the imported media, or `null` when the image could not be read or decoded.
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

    private fun processBlocking(ctx: Context, uri: Uri): ImportedMedia? {
        val sourceMime = MediaFiles.mimeOf(ctx, uri)
        val rawName = MediaFiles.displayName(ctx, uri) ?: uri.lastPathSegment

        val decoded = Bitmaps.decodeUri(ctx, uri, MAX_EDGE) ?: return null

        val resized = Bitmaps.downscale(decoded, MAX_EDGE)
        if (resized !== decoded) decoded.recycle()

        // JPEG cannot store alpha and would composite transparent pixels onto black, which looks
        // broken for stickers and screenshots — flatten onto white first.
        val flat = Bitmaps.flattenForJpeg(resized, Bitmaps.alphaCapable(sourceMime))

        try {
            val jpeg = Bitmaps.encodeJpeg(flat, JPEG_QUALITY)
            if (jpeg.isEmpty()) return null

            val transferId = newId()
            val name = MediaFiles.jpegName(rawName)
            val dest = MediaLayout.payloadFile(ctx, transferId, name)
            if (!MediaFiles.writeBytes(dest, jpeg)) return null

            val thumb = Bitmaps.buildThumbnail(
                base = flat,
                dest = MediaLayout.thumbFile(ctx, transferId),
                maxEdge = THUMB_EDGE,
                quality = THUMB_QUALITY
            )

            return ImportedMedia(
                filePath = dest.absolutePath,
                thumbPath = thumb.file?.absolutePath,
                thumbB64 = thumb.b64,
                name = name,
                mime = MIME,
                size = jpeg.size.toLong(),
                width = flat.width,
                height = flat.height,
                durationMs = 0L,
                sha256 = MediaFiles.sha256(dest)
            )
        } finally {
            flat.recycle()
        }
    }

    /**
     * Rebuilds a thumbnail for an image that is already on disk — used when a message arrived
     * without an inline preview, or when `files/thumbs` was cleaned up.
     */
    internal fun thumbnail(ctx: Context, source: File, transferId: String): File? {
        val decoded = try {
            Bitmaps.decodeFile(source, THUMB_EDGE)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        return try {
            Bitmaps.buildThumbnail(
                base = decoded,
                dest = MediaLayout.thumbFile(ctx, transferId),
                maxEdge = THUMB_EDGE,
                quality = THUMB_QUALITY
            ).file
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            decoded.recycle()
        }
    }
}
