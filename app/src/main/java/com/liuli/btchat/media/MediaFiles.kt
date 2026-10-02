package com.liuli.btchat.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.liuli.btchat.core.ImportedMedia
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where the media pipeline is allowed to write.
 *
 * This mirrors the storage contract documented on
 * [com.liuli.btchat.data.MediaVaultImpl], which delegates its directory getters here so the
 * layout has exactly one definition:
 * ```
 *   files/media/<transferId>__<name>     payloads (sent and received)
 *   files/thumbs/<transferId>.jpg        bubble thumbnails
 * ```
 */
internal object MediaLayout {

    const val MEDIA_DIR = "media"
    const val THUMB_DIR = "thumbs"

    fun mediaDir(ctx: Context): File = File(ctx.filesDir, MEDIA_DIR).ensured()

    fun thumbDir(ctx: Context): File = File(ctx.filesDir, THUMB_DIR).ensured()

    /** Destination of a payload, named the same way `MediaVaultImpl.newIncomingFile` names one. */
    fun payloadFile(ctx: Context, transferId: String, fileName: String): File =
        File(mediaDir(ctx), "${key(transferId)}__$fileName")

    /** Destination of the bubble thumbnail for one transfer. */
    fun thumbFile(ctx: Context, transferId: String): File =
        File(thumbDir(ctx), "${key(transferId)}.jpg")

    /** Keeps an id usable as a file name; blank or exotic ids still get a stable name. */
    fun key(transferId: String): String {
        val cleaned = transferId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return cleaned.ifBlank { "local" }
    }
}

private fun File.ensured(): File = apply { if (!exists()) mkdirs() }

/**
 * Naming, sniffing and streaming helpers shared by [ImagePipeline] and [VideoPipeline].
 *
 * Everything here is deliberately free of Android UI types so the pure parts (names, Base64
 * arithmetic) stay unit-testable on the JVM.
 */
internal object MediaFiles {

    private const val BUF_SIZE = 64 * 1024
    private const val MAX_NAME_CHARS = 80
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Same set `MediaVaultImpl.newIncomingFile` keeps: ASCII, CJK, dot, dash and underscore. */
    private val UNSAFE = Regex("[^A-Za-z0-9._\\u4e00-\\u9fff-]")

    // ------------------------------------------------------------------ names

    /** Replaces anything a filesystem dislikes, keeping the tail so the extension survives. */
    fun sanitize(raw: String?, fallback: String): String {
        val cleaned = raw.orEmpty().trim().replace(UNSAFE, "_").trim('.', '_', ' ', '-')
        return if (cleaned.isBlank()) fallback else cleaned.takeLast(MAX_NAME_CHARS)
    }

    /** File name without its extension. */
    fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** Lower-case extension without the dot, or `""`. */
    fun extensionOf(name: String?): String {
        val n = name.orEmpty()
        val dot = n.lastIndexOf('.')
        return if (dot in 1 until n.length) n.substring(dot + 1).lowercase() else ""
    }

    /** `IMG_1234.png` → `IMG_1234.jpg`: the payload is always re-encoded to JPEG. */
    fun jpegName(raw: String?): String {
        val base = stem(sanitize(raw, "image")).take(64).trim('.', '_', '-')
        return base.ifBlank { "image" } + ".jpg"
    }

    /** Keeps the original extension when there is one, otherwise derives it from [mime]. */
    fun videoName(raw: String?, mime: String): String {
        val safe = sanitize(raw, "video")
        return if (extensionOf(safe).isNotEmpty()) safe else "$safe.${videoExtension(mime)}"
    }

    /** File extension for a video MIME type. */
    fun videoExtension(mime: String?): String = when (mime?.lowercase()?.substringBefore(';')?.trim()) {
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo" -> "avi"
        else -> "mp4"
    }

    /**
     * Canonical file name of a voice payload: `voice_<8 chars of the id>.m4a`.
     *
     * The recorder and [com.liuli.btchat.data.MediaVaultImpl.voiceFile] both go through this, so
     * a voice note has one predictable name on both ends of the link.
     */
    fun voiceFileName(id: String): String {
        val short = id.filter { it.isLetterOrDigit() }.take(8).ifBlank { "clip" }
        return "voice_$short.m4a"
    }

    /** Best-effort MIME type from a file name; `""` when nothing is recognised. */
    fun mimeFromName(name: String?): String = when (extensionOf(name)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "avif" -> "image/avif"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "m4a", "mp4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "amr" -> "audio/amr"
        else -> ""
    }

    // --------------------------------------------------------------- resolver

    /** `OpenableColumns.DISPLAY_NAME`, i.e. the name the user sees in the gallery. */
    fun displayName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && c.moveToFirst()) c.getString(column) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Declared payload size, or `-1` when the provider does not publish one. */
    fun sizeOf(ctx: Context, uri: Uri): Long = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val column = c.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && c.moveToFirst()) c.getLong(column) else -1L
        }
    }.getOrNull() ?: -1L

    fun mimeOf(ctx: Context, uri: Uri): String? =
        runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
            ?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }

    fun openInput(ctx: Context, uri: Uri): InputStream? =
        runCatching { ctx.contentResolver.openInputStream(uri) }.getOrNull()

    // --------------------------------------------------------------------- io

    /**
     * Streams [uri] into [dest] while hashing it, so a large video is copied **and** verified
     * in a single pass without ever being held in memory.
     *
     * @return the byte count and SHA-256, or `null` after cleaning up a failed copy.
     */
    fun copyIn(ctx: Context, uri: Uri, dest: File): Payload? {
        val input = openInput(ctx, uri) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            input.use { ins ->
                dest.parentFile?.mkdirs()
                dest.outputStream().buffered(BUF_SIZE).use { out ->
                    val buf = ByteArray(BUF_SIZE)
                    while (true) {
                        val read = ins.read(buf)
                        if (read < 0) break
                        if (read == 0) continue
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        total += read
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { dest.delete() }
            return null
        }
        return Payload(total, hex(digest.digest()))
    }

    /** SHA-256 of an existing file, read in 64 KiB chunks. `""` when the file cannot be read. */
    fun sha256(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUF_SIZE).use { ins ->
            val buf = ByteArray(BUF_SIZE)
            while (true) {
                val read = ins.read(buf)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buf, 0, read)
            }
        }
        hex(digest.digest())
    } catch (e: Exception) {
        ""
    }

    /**
     * Writes [bytes] to [file], creating parent directories.
     *
     * A failed write removes what it left behind. With media now auto-accepted, a half-written
     * thumbnail or payload must never survive looking like a complete file — and on a full disk
     * that is the normal failure, not an exotic one.
     */
    fun writeBytes(file: File, bytes: ByteArray): Boolean = try {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    } catch (e: Exception) {
        runCatching { file.delete() }
        false
    }

    /**
     * Cheap magic-number check used before trusting a thumbnail that arrived over the air,
     * so a peer cannot make us write an arbitrary blob into `files/thumbs`.
     */
    fun looksLikeImage(bytes: ByteArray): Boolean = when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> true

        bytes.size >= 8 && bytes[0] == 0x89.toByte() && ascii(bytes, 1, 3) == "PNG" -> true
        bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" -> true
        bytes.size >= 6 && ascii(bytes, 0, 4) == "GIF8" -> true
        else -> false
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (bytes.size < offset + length) "" else String(bytes, offset, length, Charsets.US_ASCII)

    // ----------------------------------------------------------------- base64

    /** Exact length of the Base64 text Android emits with `NO_WRAP` (padded) for [bytes] bytes. */
    fun base64Length(bytes: Int): Int = ((bytes + 2) / 3) * 4

    /** Largest binary payload that still fits into [maxChars] Base64 characters. */
    fun base64ByteBudget(maxChars: Int): Int = (maxChars / 4) * 3

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** A payload that was copied into app storage: exact byte count plus its streamed digest. */
    data class Payload(val size: Long, val sha256: String)
}

/**
 * Deletes the payload and thumbnail an import already produced.
 *
 * Importing writes into `files/media` before the user has agreed to anything, so a cancelled
 * "this file is large, send it anyway?" confirmation would otherwise leave a file whose name
 * nothing in the app remembers — an orphan that no message deletion can reach. Call this when a
 * confirmed-before-send flow is abandoned.
 *
 * Pure file work: no `Context`, so it is safe from any thread and testable on the JVM.
 *
 * @return `true` when nothing of the import is left on disk.
 */
fun discardImportedFiles(imported: ImportedMedia): Boolean {
    var clean = true
    val paths = listOfNotNull(imported.filePath, imported.thumbPath)
    for (path in paths) {
        val file = File(path)
        if (file.exists() && !file.delete()) clean = false
    }
    return clean
}
