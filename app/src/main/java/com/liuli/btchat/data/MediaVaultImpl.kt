package com.liuli.btchat.data

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.liuli.btchat.LiuliApp
import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.MediaVault
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.media.ImagePipeline
import com.liuli.btchat.media.MediaFiles
import com.liuli.btchat.media.MediaLayout
import com.liuli.btchat.media.VideoPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns every byte the app stores on disk.
 *
 * Owner: `media-pipeline`. Storage layout (defined once in
 * [com.liuli.btchat.media.MediaLayout]):
 * ```
 *   files/media/<transferId>__<name>     payloads (sent and received)
 *   files/thumbs/<transferId>.jpg        bubble thumbnails
 *   cache/share/…                        transient copies handed to other apps
 * ```
 *
 * Importing is delegated to the two pipelines, which do the compressing, the EXIF rotation and
 * the thumbnail shrinking; everything else here is pure storage.
 */
object MediaVaultImpl : MediaVault {

    /** A peer may not push an arbitrarily large blob into our thumbnail folder. */
    private const val MAX_INCOMING_THUMB_B64 = 64 * 1024
    private const val MAX_INCOMING_THUMB_BYTES = 48 * 1024

    private val ctx get() = LiuliApp.instance

    private val mediaDir: File
        get() = MediaLayout.mediaDir(ctx)

    private val thumbDir: File
        get() = MediaLayout.thumbDir(ctx)

    private val shareDir: File
        get() = File(ctx.cacheDir, "share").apply { if (!exists()) mkdirs() }

    // ------------------------------------------------------------ import path

    /** Compresses the picked picture (≤1600 px JPEG q80) and stores it with its thumbnail. */
    override suspend fun importImage(uri: Uri): ImportedMedia? = ImagePipeline.process(ctx, uri)

    /** Copies the picked video verbatim (no transcoder available) and grabs a preview frame. */
    override suspend fun importVideo(uri: Uri): ImportedMedia? = VideoPipeline.process(ctx, uri)

    /**
     * Picks image-or-video by sniffing the content type.
     *
     * The sniff is two `ContentResolver` queries, so it runs on [Dispatchers.IO] like every other
     * piece of IO here — the pipelines switch internally, but this part is on us.
     */
    override suspend fun importAny(uri: Uri): ImportedMedia? {
        val (name, mime) = withContext(Dispatchers.IO) {
            val display = MediaFiles.displayName(ctx, uri) ?: uri.lastPathSegment
            display to (MediaFiles.mimeOf(ctx, uri) ?: MediaFiles.mimeFromName(display))
        }
        return when {
            mime.startsWith("video/") -> importVideo(uri)
            mime.startsWith("image/") -> importImage(uri)
            // Unknown type: let the decoders decide, images first — they are far more common.
            else -> importImage(uri) ?: importVideo(uri)
        }
    }

    /**
     * Resolves a small bitmap for a bubble, in order of cost:
     *  1. the thumbnail already on disk;
     *  2. the Base64 preview that arrived with the offer, written out once;
     *  3. a freshly decoded thumbnail from the payload we already hold.
     */
    override suspend fun thumbnail(att: Attachment): File? = withContext(Dispatchers.IO) {
        existingThumb(att.thumbPath)?.let { return@withContext it }
        // The canonical slot may already be filled even when the attachment record is not.
        existingThumb(MediaLayout.thumbFile(ctx, att.transferId).absolutePath)?.let { return@withContext it }

        storeIncomingThumb(att.transferId, att.thumbB64)?.let { path ->
            existingThumb(path)?.let { return@withContext it }
        }

        val source = att.localPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }
            ?: return@withContext null

        when {
            att.kind == MsgKind.VIDEO || att.mime.startsWith("video/") ->
                VideoPipeline.thumbnail(ctx, source, att.transferId)

            att.kind == MsgKind.IMAGE || att.mime.startsWith("image/") ->
                ImagePipeline.thumbnail(ctx, source, att.transferId)

            // A voice note has no picture: its bubble draws a waveform decoded from the audio.
            else -> null
        }
    }

    private fun existingThumb(path: String?): File? =
        path?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }

    // ------------------------------------------------------------- resolution

    override fun fileFor(att: Attachment): File? =
        att.localPath?.let { File(it) }?.takeIf { it.exists() }

    /**
     * Writes an incoming Base64 thumbnail to disk.
     *
     * The payload is validated first — a peer must not be able to make us write an arbitrary
     * blob into `files/thumbs`, so anything that is not a small, image-shaped payload is dropped.
     */
    override fun storeIncomingThumb(transferId: String, b64: String?): String? {
        if (b64.isNullOrBlank() || b64.length > MAX_INCOMING_THUMB_B64) return null
        return runCatching {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size > MAX_INCOMING_THUMB_BYTES) return@runCatching null
            if (!MediaFiles.looksLikeImage(bytes)) return@runCatching null

            val file = MediaLayout.thumbFile(ctx, transferId)
            if (!MediaFiles.writeBytes(file, bytes)) return@runCatching null
            file.absolutePath
        }.getOrNull()
    }

    override fun newIncomingFile(transferId: String, fileName: String): File {
        val safe = fileName.replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fff-]"), "_").takeLast(80)
        return File(mediaDir, "${transferId}__$safe")
    }

    /**
     * Canonical on-disk slot for a voice payload with this id.
     *
     * The recorder writes outgoing takes through the same naming rule, and incoming ones are
     * allocated by the engine from the name that travelled in the offer, so a voice note ends up
     * with one predictable file name on both ends of the link.
     */
    fun voiceFile(transferId: String): File =
        MediaLayout.payloadFile(ctx, transferId, MediaFiles.voiceFileName(transferId))

    override fun deleteTransferFiles(transferId: String) {
        runCatching { MediaLayout.thumbFile(ctx, transferId).delete() }
        runCatching {
            mediaDir.listFiles { f -> f.name.startsWith("${transferId}__") }?.forEach { it.delete() }
        }
    }

    /**
     * Deletes whatever the attachment actually points at.
     *
     * An outgoing payload lands under the id minted at import time, which is a
     * different id from the transfer the engine later records, so deleting by
     * transfer id alone would leave the file behind. Paths win; the transfer id
     * is a best-effort sweep for anything not referenced by path.
     */
    override fun deleteAttachmentFiles(att: Attachment) {
        att.localPath?.let { runCatching { File(it).delete() } }
        att.thumbPath?.let { runCatching { File(it).delete() } }
        deleteTransferFiles(att.transferId)
    }

    // --------------------------------------------------------------- sharing

    override fun shareUri(file: File): Uri =
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)

    override fun exportToGallery(file: File, mime: String): Boolean = runCatching {
        val isVideo = mime.startsWith("video")
        val collection =
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/琉璃")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(collection, values) ?: return@runCatching false
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrDefault(false)
}
