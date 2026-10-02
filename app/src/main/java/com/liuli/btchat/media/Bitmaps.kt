package com.liuli.btchat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * All bitmap work of the media pipeline: decode, orient, scale, JPEG-encode and shrink a
 * thumbnail until its Base64 form fits inside a FILE_OFFER.
 *
 * Two rules shape everything in this file:
 *  * **Never allocate the full-size image.** Decoding is sampled twice — once by bounds, once
 *    with a computed `inSampleSize` — and the sampled edge is never more than 2x the target, so
 *    a 108 MP panorama costs roughly the memory of the picture that is actually sent.
 *  * **Never leak a bitmap.** Every function documents whether it consumes its input; the
 *    object none of them ever calls `recycle()` on is the caller's own bitmap.
 */
internal object Bitmaps {

    /** Longest edge of a picture that is worth sending — 1600 px is crisp on a phone screen. */
    const val IMAGE_MAX_EDGE = 1600

    /** Longest edge of the bubble thumbnail written to `files/thumbs`. */
    const val THUMB_MAX_EDGE = 360

    /** Longest edge of a frame grabbed out of a video. */
    const val VIDEO_THUMB_MAX_EDGE = 480

    /** Hard cap for the Base64 thumbnail that travels inside FILE_OFFER: 12 KiB of text. */
    const val THUMB_MAX_B64_CHARS = 12 * 1024

    /** Decode at most this multiple of the target edge, then scale down exactly. */
    private const val DECODE_SLACK = 2

    /** Upper bound for one decoded bitmap, ~24 MB at ARGB_8888. */
    private const val DECODE_PIXEL_BUDGET = 6_000_000

    private const val MAX_SAMPLE_SIZE = 64
    private const val MIN_LADDER_EDGE = 24

    /**
     * Quality/size steps tried in order when a thumbnail has to shrink to fit the offer budget.
     * Quality drops first (cheap, keeps the composition readable), then the edge halves down.
     */
    private val THUMB_LADDER = listOf(
        360 to 75, 360 to 62, 320 to 55, 288 to 50, 256 to 46, 224 to 42,
        192 to 38, 160 to 34, 128 to 32, 96 to 30, 64 to 28, 48 to 26
    )

    /** A thumbnail on disk plus the small copy that rides the offer. Both may be absent. */
    class Thumb(val file: File?, val b64: String?)

    // ------------------------------------------------------------------ decode

    /**
     * Decodes [uri] with its longest edge bounded by [maxEdge], applying EXIF orientation.
     * Returns `null` (never throws) when the image cannot be decoded or does not fit in memory.
     */
    fun decodeUri(ctx: Context, uri: Uri, maxEdge: Int): Bitmap? =
        decodeUriViaImageDecoder(ctx, uri, maxEdge) ?: decodeUriViaFactory(ctx, uri, maxEdge)

    /** File-backed variant used when a thumbnail has to be rebuilt from a stored payload. */
    fun decodeFile(file: File, maxEdge: Int): Bitmap? =
        decodeFileViaImageDecoder(file, maxEdge) ?: decodeFileViaFactory(file, maxEdge)

    /**
     * Power-of-two sample factor that brings the long edge to at most `2 * maxEdge` without
     * dropping below [maxEdge] when that is avoidable, and keeps the decoded bitmap inside
     * [DECODE_PIXEL_BUDGET] pixels. Works on the *long* edge only, so EXIF rotation (which
     * swaps width and height) cannot change the result.
     */
    fun sampleSizeFor(w: Int, h: Int, maxEdge: Int, pixelBudget: Int = DECODE_PIXEL_BUDGET): Int {
        if (w <= 0 || h <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (sample < MAX_SAMPLE_SIZE) {
            val sw = w / sample
            val sh = h / sample
            val edgeOk = max(sw, sh) <= maxEdge * DECODE_SLACK
            val memoryOk = sw.toLong() * sh.toLong() <= pixelBudget
            if (edgeOk && memoryOk) break
            val next = sample * 2
            if (w / next <= 0 || h / next <= 0) break
            sample = next
        }
        return sample
    }

    /**
     * `ImageDecoder` is the primary path: it honours EXIF orientation itself, streams the source
     * and lets us pick the sample size from the header before a single pixel is allocated.
     */
    private fun decodeUriViaImageDecoder(ctx: Context, uri: Uri, maxEdge: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height, maxEdge))
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun decodeFileViaImageDecoder(file: File, maxEdge: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSampleSize(sampleSizeFor(info.size.width, info.size.height, maxEdge))
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /**
     * `BitmapFactory` fallback for the formats `ImageDecoder` refuses (BMP, odd CMYK JPEGs …).
     * It has no idea about EXIF, so the orientation is read from the source and applied here.
     */
    private fun decodeUriViaFactory(ctx: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = MediaFiles.openInput(ctx, uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = try {
            val stream = MediaFiles.openInput(ctx, uri) ?: return null
            stream.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        return applyExifOrientation(raw, readExifOrientation(ctx, uri))
    }

    private fun decodeFileViaFactory(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = try {
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null

        return applyExifOrientation(raw, readExifOrientation(file))
    }

    /**
     * Reads the EXIF orientation straight out of the framework parser.
     *
     * `androidx.exifinterface` is not on the compile classpath of this module (Coil pulls it in
     * at runtime only), so the platform reader is the dependency-free way to reach EXIF data for
     * the `BitmapFactory` fallback. The primary `ImageDecoder` path applies orientation itself.
     */
    @Suppress("DEPRECATION")
    private fun readExifOrientation(ctx: Context, uri: Uri): Int {
        val normal = android.media.ExifInterface.ORIENTATION_NORMAL
        val stream = MediaFiles.openInput(ctx, uri) ?: return normal
        return try {
            BufferedInputStream(stream, 16 * 1024).use { buffered ->
                android.media.ExifInterface(buffered)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, normal)
            }
        } catch (e: Exception) {
            normal
        }
    }

    @Suppress("DEPRECATION")
    private fun readExifOrientation(file: File): Int = try {
        android.media.ExifInterface(file.absolutePath).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
    } catch (e: Exception) {
        android.media.ExifInterface.ORIENTATION_NORMAL
    }

    /**
     * Bakes the EXIF orientation into the pixels. Consumes [src] when a rotated copy is created.
     */
    @Suppress("DEPRECATION")
    fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return src
        }
        return try {
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (out !== src) src.recycle()
            out
        } catch (e: OutOfMemoryError) {
            src
        }
    }

    // --------------------------------------------------------------- transform

    /**
     * Scales [src] down to a long edge of [maxEdge], halving repeatedly first so the result is
     * not a badly aliased one-shot resample.
     *
     * Does **not** recycle [src]. The returned bitmap is either [src] itself (nothing to do) or
     * a new bitmap that the caller now owns.
     */
    fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        if (maxEdge <= 0) return src

        var work = src
        var owned: Bitmap? = null
        while (max(work.width, work.height) > maxEdge * DECODE_SLACK && work.width > 1 && work.height > 1) {
            val next = Bitmap.createScaledBitmap(work, max(1, work.width / 2), max(1, work.height / 2), true)
            owned?.recycle()
            owned = next
            work = next
        }

        val longEdge = max(work.width, work.height)
        if (longEdge <= maxEdge) return owned ?: src

        val scale = maxEdge.toFloat() / longEdge
        val targetW = max(1, (work.width * scale).roundToInt())
        val targetH = max(1, (work.height * scale).roundToInt())
        if (targetW == work.width && targetH == work.height) return owned ?: src

        val out = Bitmap.createScaledBitmap(work, targetW, targetH, true)
        if (out !== work) owned?.recycle()
        return out
    }

    /** Whether a source of this MIME type can carry transparency that JPEG would destroy. */
    fun alphaCapable(mime: String?): Boolean {
        val m = mime?.lowercase().orEmpty()
        if (m.isEmpty()) return true
        return m.contains("png") || m.contains("webp") || m.contains("gif") ||
            m.contains("avif") || m.contains("heic") || m.contains("heif")
    }

    /**
     * JPEG has no alpha channel and composites transparent pixels onto black, which looks broken
     * for a sticker or a screenshot. Draws such images onto white first.
     *
     * Consumes [src] when a flattened copy is created.
     */
    fun flattenForJpeg(src: Bitmap, alphaCapableSource: Boolean): Bitmap {
        if (!alphaCapableSource || !src.hasAlpha()) return src
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Canvas(out).apply {
                drawColor(Color.WHITE)
                drawBitmap(src, 0f, 0f, null)
            }
            src.recycle()
            out
        } catch (e: OutOfMemoryError) {
            src
        } catch (e: Exception) {
            src
        }
    }

    // ----------------------------------------------------------------- encode

    fun encodeJpeg(src: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(96 * 1024)
        src.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fitsBase64(bytes: ByteArray, maxChars: Int): Boolean =
        bytes.isNotEmpty() && MediaFiles.base64Length(bytes.size) <= maxChars

    /**
     * Returns the smallest JPEG that encodes [base] into at most [maxChars] Base64 characters.
     *
     * [known] is an encode of [base] the caller already produced (the on-disk thumbnail); it is
     * reused when it already fits. Otherwise the [THUMB_LADDER] walks quality down and size down
     * until the budget is met, and as a last resort the edge keeps halving — a thumbnail is then
     * a few hundred bytes, so the loop always terminates. Does not recycle [base].
     *
     * @return `null` only if even the smallest step somehow overflows, which no real JPEG does.
     */
    fun fitBase64(base: Bitmap, maxChars: Int, known: ByteArray? = null): ByteArray? {
        val budget = MediaFiles.base64ByteBudget(maxChars)
        if (known != null && known.isNotEmpty() && known.size <= budget) return known

        for ((edge, quality) in THUMB_LADDER) {
            val bytes = encodeScaled(base, edge, quality)
            if (bytes != null && bytes.size <= budget) return bytes
        }

        var edge = THUMB_LADDER.last().first
        while (edge > MIN_LADDER_EDGE) {
            edge /= 2
            val bytes = encodeScaled(base, edge, 24)
            if (bytes != null && bytes.size <= budget) return bytes
        }
        return null
    }

    /** Encodes a [downscale]d copy of [base] and frees the intermediate. */
    private fun encodeScaled(base: Bitmap, edge: Int, quality: Int): ByteArray? {
        val scaled = downscale(base, edge)
        return try {
            encodeJpeg(scaled, quality).takeIf { it.isNotEmpty() }
        } finally {
            if (scaled !== base) scaled.recycle()
        }
    }

    /**
     * Produces the bubble thumbnail for [base]: a [maxEdge]-bounded JPEG at [dest] (when [dest]
     * is writable) plus the Base64 copy for the offer. Does not recycle [base].
     */
    fun buildThumbnail(
        base: Bitmap,
        dest: File?,
        maxEdge: Int,
        quality: Int,
        maxB64Chars: Int = THUMB_MAX_B64_CHARS
    ): Thumb {
        val disk = downscale(base, maxEdge)
        return try {
            val jpeg = encodeJpeg(disk, quality)
            val file = if (dest != null && jpeg.isNotEmpty() && MediaFiles.writeBytes(dest, jpeg)) dest else null
            val wire = fitBase64(disk, maxB64Chars, jpeg)
            Thumb(file, wire?.let { base64(it) })
        } finally {
            if (disk !== base) disk.recycle()
        }
    }
}
