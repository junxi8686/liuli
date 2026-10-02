package com.liuli.btchat.media

import com.liuli.btchat.core.TimeFmt

/**
 * Size policy for outgoing media.
 *
 * Videos are sent byte-for-byte (there is no transcoder in the app), so the only lever left is
 * refusing or discouraging payloads that would occupy the Bluetooth link for an unreasonable
 * amount of time. Images never hit this path: [ImagePipeline] already compresses them to a few
 * hundred kilobytes.
 */
object MediaLimits {

    /** Hard ceiling for one outgoing payload: 50 MB. Above this the UI must refuse to send. */
    const val MAX_SEND_BYTES: Long = 50L * 1024 * 1024

    /** From here on the UI warns about the transfer time but still allows the send: 15 MB. */
    const val WARN_SEND_BYTES: Long = 15L * 1024 * 1024

    /** `true` when [size] must not be sent at all. */
    fun exceedsLimit(size: Long): Boolean = size > MAX_SEND_BYTES

    /** `true` when [size] is allowed but slow enough to deserve a warning. */
    fun isWarning(size: Long): Boolean = size in WARN_SEND_BYTES..MAX_SEND_BYTES

    /**
     * Text to show next to the send button, or `null` when the payload is small enough to go
     * without comment.
     *
     * * above [MAX_SEND_BYTES] — why it cannot be sent, plus the time it would have taken;
     * * from [WARN_SEND_BYTES] up — the transfer time so the user can decide;
     * * anything non-positive — `null`, there is nothing useful to say about an empty file.
     */
    fun check(size: Long): String? = when {
        size <= 0L -> null

        size > MAX_SEND_BYTES -> "文件 ${TimeFmt.size(size)} 超过 50 MB 上限，蓝牙传输预计" +
            MediaEstimates.humanDuration(MediaEstimates.transferSeconds(size)) +
            "，请先裁剪或压缩后再发送"

        size >= WARN_SEND_BYTES -> "文件较大（${TimeFmt.size(size)}），蓝牙传输预计" +
            MediaEstimates.humanDuration(MediaEstimates.transferSeconds(size)) +
            "，建议两台设备保持靠近"

        else -> null
    }
}
