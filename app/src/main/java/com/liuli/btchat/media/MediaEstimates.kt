package com.liuli.btchat.media

/**
 * How long a payload will take to cross the Bluetooth link.
 *
 * RFCOMM on real hardware measures roughly 100–200 KB/s depending on the phone pair and on
 * how much other traffic the adapter is carrying. The UI uses the middle of that range so an
 * estimate is neither a promise nor a scare tactic.
 */
object MediaEstimates {

    /** Median RFCOMM throughput used for every ETA in the UI: 150 KB/s. */
    const val BYTES_PER_SECOND: Long = 150L * 1024

    /**
     * Seconds needed to push [bytes] at [BYTES_PER_SECOND], rounded **up** so the UI never
     * promises a transfer that is physically impossible. `0` for an empty payload.
     *
     * Saturates at [Int.MAX_VALUE] instead of overflowing, because callers feed this
     * whatever file size the system handed them.
     */
    fun transferSeconds(bytes: Long): Int {
        if (bytes <= 0L) return 0
        val whole = bytes / BYTES_PER_SECOND
        val seconds = if (bytes % BYTES_PER_SECOND == 0L) whole else whole + 1
        return seconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * [transferSeconds] against a **measured** rate instead of the nominal one.
     *
     * A caller that has a real throughput reading — the transport measures every byte it writes —
     * should pass it, because the nominal 150 KB/s is only a way to size a file before anything
     * has been observed. A non-positive [bytesPerSecond] falls back to [BYTES_PER_SECOND].
     */
    fun transferSeconds(bytes: Long, bytesPerSecond: Long): Int {
        if (bytes <= 0L) return 0
        val rate = if (bytesPerSecond > 0L) bytesPerSecond else BYTES_PER_SECOND
        val whole = bytes / rate
        val seconds = if (bytes % rate == 0L) whole else whole + 1
        return seconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Chinese ETA label for [seconds]: `约 30 秒` / `约 4 分钟` / `约 1 小时 6 分钟`.
     * Minutes are also rounded up — the estimate shown to the user is always the pessimistic one.
     */
    fun humanDuration(seconds: Int): String {
        if (seconds <= 0) return "不到 1 秒"
        if (seconds < 60) return "约 $seconds 秒"
        if (seconds < 3600) return "约 ${(seconds + 59) / 60} 分钟"
        val hours = seconds / 3600
        val minutes = (seconds % 3600 + 59) / 60
        return if (minutes == 0) "约 $hours 小时" else "约 $hours 小时 $minutes 分钟"
    }

    /** Ready-to-display sentence for a payload size, e.g. `预计传输约 2 分钟`. */
    fun transferText(bytes: Long): String = "预计传输" + humanDuration(transferSeconds(bytes))
}

/**
 * File-level alias so callers can write either `transferSeconds(x)` or
 * `MediaEstimates.transferSeconds(x)` — both are the same function.
 */
fun transferSeconds(bytes: Long): Int = MediaEstimates.transferSeconds(bytes)
