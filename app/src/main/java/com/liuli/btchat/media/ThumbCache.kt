package com.liuli.btchat.media

import android.util.LruCache
import java.io.File

/**
 * In-memory LRU cache of encoded thumbnails, so a chat list can be scrolled without hitting the
 * filesystem — and without re-reading the same JPEG on every recomposition.
 *
 * The budget is an eighth of the heap (clamped to 2–64 MB): enough for several hundred bubble
 * thumbnails, small enough that a low-RAM phone never trades image caching for decoding headroom.
 * Entries are raw JPEG bytes, so the cache is also directly reusable by an image loader.
 */
object ThumbCache {

    private const val MIN_BYTES = 2 * 1024 * 1024
    private const val MAX_BYTES = 64 * 1024 * 1024

    /** One eighth of the available heap, clamped to a sane range. */
    val capacity: Int = run {
        val heap = Runtime.getRuntime().maxMemory()
        val eighth = if (heap <= 0L || heap == Long.MAX_VALUE) MIN_BYTES.toLong() else heap / 8
        eighth.coerceIn(MIN_BYTES.toLong(), MAX_BYTES.toLong()).toInt()
    }

    private val lru = object : LruCache<String, ByteArray>(capacity) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** Bytes currently held. */
    val sizeBytes: Int get() = lru.size()

    /** Cache key for a thumbnail file. */
    fun key(file: File): String = file.absolutePath

    fun get(path: String): ByteArray? = lru.get(path)?.takeIf { it.isNotEmpty() }

    fun put(path: String, jpeg: ByteArray) {
        if (jpeg.isNotEmpty()) lru.put(path, jpeg)
    }

    /**
     * Cached bytes for [path], loading them from disk on a miss.
     *
     * @return `null` when the file does not exist or cannot be read.
     */
    fun read(path: String): ByteArray? {
        get(path)?.let { return it }
        val file = File(path)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        put(path, bytes)
        return bytes
    }

    fun evict(path: String) {
        lru.remove(path)
    }

    fun clear() {
        lru.evictAll()
    }
}
