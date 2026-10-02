package com.liuli.btchat.core

import java.util.UUID

/**
 * Local domain model. Frozen contract — every module codes against these types.
 * Nothing here touches Android or the wire format.
 */

enum class MsgKind { TEXT, IMAGE, VIDEO, VOICE, VOICE_PTT, FILE, SYSTEM }

/** Local lifecycle of a message bubble. */
enum class MsgState { DRAFT, SENDING, SENT, DELIVERED, READ, FAILED }

enum class ConvKind { DIRECT, GROUP }

enum class Role { OWNER, ADMIN, MEMBER }

/** Lifecycle of an attachment transfer. */
enum class TransferState {
    IDLE,
    OFFERED,
    WAIT_ACCEPT,

    /**
     * The link to whoever holds the bytes is too slow to be worth starting.
     * Deliberately not FAILED: nothing has gone wrong, and the transfer resumes
     * by itself once the link recovers — so the UI must say "waiting", never
     * "failed".
     */
    WAITING_LINK,

    /**
     * The offer arrived (possibly relayed through several phones) but nobody
     * currently online holds the bytes. Saying so beats pretending to download.
     */
    WAITING_SENDER,

    TRANSFERRING,
    DONE,
    FAILED,
    REJECTED,
    CANCELLED
}

/** Where a peer came from — drives the badge shown in the list. */
enum class PeerSource { BONDED, DISCOVERED, CONNECTED, SAVED }

data class Attachment(
    val transferId: String,
    val kind: MsgKind,
    val fileName: String,
    val mime: String,
    val size: Long,
    val sha256: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    /** Absolute path of the received/sent payload once it exists locally. */
    val localPath: String? = null,
    /** Absolute path of the local thumbnail. */
    val thumbPath: String? = null,
    /** Base64 JPEG thumbnail carried inside the offer so the peer can render
     *  a preview before the payload arrives. */
    val thumbB64: String? = null,
    val progress: Float = 0f,
    val state: TransferState = TransferState.IDLE,
    val transferred: Long = 0L
)

/** A quoted message, flattened so the bubble can render it without a lookup. */
data class Quote(
    val msgId: String,
    val senderName: String,
    val preview: String
)

data class Message(
    val id: String,
    val convId: String,
    val senderId: String,
    val senderName: String,
    val kind: MsgKind,
    val text: String = "",
    val attachment: Attachment? = null,
    val sentAt: Long = System.currentTimeMillis(),
    val state: MsgState = MsgState.SENT,
    val outgoing: Boolean = false,
    /** deviceIds that have acknowledged this message (groups). */
    val readBy: Set<String> = emptySet(),
    /** What this message is replying to, if anything. */
    val quote: Quote? = null,
    /** Recalled by its sender: the bubble becomes a tombstone on both sides. */
    val recalled: Boolean = false,
    /** Kept in "我的收藏". */
    val starred: Boolean = false
) {
    val preview: String
        get() = when {
            recalled -> "[已撤回]"
            kind == MsgKind.TEXT -> text
            kind == MsgKind.IMAGE -> "[图片]"
            kind == MsgKind.VIDEO -> "[视频]"
            kind == MsgKind.VOICE -> "[语音]"
            kind == MsgKind.VOICE_PTT -> "[对讲]"
            kind == MsgKind.FILE -> "[文件] ${attachment?.fileName ?: ""}"
            else -> text
        }

    /**
     * Only a settled message can be recalled.
     *
     * The KDoc always said media still on the wire cannot be recalled
     * meaningfully, but the code never checked — so 「撤回」 showed up on an
     * attachment that was still transferring, tombstoned the message locally,
     * and left the transfer running: the sender kept burning bandwidth, the
     * receiver kept writing to disk, and the finished file lost its only
     * reference (IMAGE/VIDEO even landed in the system gallery). Refusing the
     * menu item until the transfer settles is the honest fix.
     */
    val canRecall: Boolean
        get() = outgoing && !recalled && kind != MsgKind.SYSTEM &&
            attachment?.state?.let { it == TransferState.DONE || it == TransferState.FAILED } != false &&
            System.currentTimeMillis() - sentAt < RECALL_WINDOW_MS

    companion object {
        /** WeChat's window is two minutes; so is this one. */
        const val RECALL_WINDOW_MS = 2 * 60 * 1000L
    }
}

data class Member(
    val deviceId: String,
    val name: String,
    val avatarSeed: Int,
    val role: Role = Role.MEMBER,
    val joinedAt: Long = System.currentTimeMillis(),
    val online: Boolean = false,
    /** 该成员被禁言：可以看，不能发。 */
    val muted: Boolean = false
)

data class Conversation(
    val id: String,
    val kind: ConvKind,
    /** For DIRECT this mirrors the contact name; for GROUP it is the group name. */
    val title: String,
    /** deviceId of the other side for DIRECT, null for GROUP. */
    val peerId: String? = null,
    val avatarSeed: Int = 0,
    val lastMessageAt: Long = 0L,
    val lastPreview: String = "",
    val unread: Int = 0,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val draft: String = "",
    /** Local device is the group owner — the relay and the member list live here. */
    val iAmOwner: Boolean = false,
    val memberCount: Int = 0,
    /** 群公告：任何人都能看，群主/管理员能改。 */
    val announcement: String = "",
    val announcementBy: String = "",
    val announcementAt: Long = 0L,
    /** 全员禁言：只有群主/管理员还能发言。 */
    val muteAll: Boolean = false
)

data class Contact(
    val deviceId: String,
    val name: String,
    /** User-set remark; shown instead of [name] when present. */
    val remark: String = "",
    val address: String = "",
    val avatarSeed: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = 0L
) {
    val display: String get() = remark.ifBlank { name }
}

data class Peer(
    val address: String,
    val deviceId: String,
    val name: String,
    val avatarSeed: Int = 0,
    val source: PeerSource = PeerSource.DISCOVERED,
    val rssi: Int = 0,
    val connected: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val key: String get() = deviceId.ifBlank { address }
}

/**
 * Values of [Prefs.effectMode].
 *
 * Lives in `core` rather than next to `GlassMode` in `ui/glass` because the
 * settings layer has to be able to say "off" without depending on the UI.
 */
object GlassModeIds {
    const val LIQUID = 0
    const val BLUR = 1

    /** No blur, no refraction — plain opaque surfaces. The only mode the UI offers. */
    const val NONE = 2
}

/** Everything the settings screen reads and writes. */
data class Prefs(
    val myDeviceId: String = "",
    val myName: String = "",
    val myAvatarSeed: Int = 0,
    val myStatus: String = "在蓝牙上",
    val discoverable: Boolean = true,
    val autoAcceptMedia: Boolean = false,
    /** Material strength, 0…1 — scales blur radius, tint and shadow. */
    val glassIntensity: Float = 1f,
    /** 0 = 液态玻璃, 1 = 普通高斯模糊, 2 = 全部关闭. See `ui.glass.GlassMode`. */
    /**
     * 0 = 液态玻璃, 1 = 高斯模糊, 2 = 全部关闭. See [GlassModeIds].
     *
     * **This default matters more than it looks.** `Settings.flow` starts as
     * `MutableStateFlow(Prefs())` and the real values are only read in
     * `ensureIdentity()`, which runs from a `LaunchedEffect` — i.e. *after* the
     * first composition. So whatever is here is what the app draws its first
     * frame with. At `1` the first frame ran with blur switched on, which made
     * `GlassStage` record its content into a backdrop layer; the RenderThread
     * died of a stack overflow in `RenderNode::prepareTreeImpl` (a node
     * referencing itself) and the window never drew again — a white screen with
     * a perfectly healthy UI thread, which is why the accessibility tree still
     * showed every button.
     *
     * With `NONE` the first frame already skips the layer entirely and the
     * settings file never has a chance to matter.
     */
    val effectMode: Int = GlassModeIds.NONE,
    val bubbleStyle: Int = 0,
    /** 0 = 跟随系统, 1 = 强制浅色, 2 = 强制深色. See `ui.glass.ThemeMode`. */
    val themeMode: Int = 0,
    /** Kept for backwards compatibility; [themeMode] is what the UI writes. */
    val darkMode: Boolean = false,
    val blurRadius: Float = 1f,
    val soundOn: Boolean = true,
    val vibrateOn: Boolean = true
)

/** Result of importing a local file into the app's own storage. */
data class ImportedMedia(
    val filePath: String,
    val thumbPath: String?,
    val thumbB64: String?,
    val name: String,
    val mime: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val sha256: String = "",
    /**
     * True when this recording came from 对讲机 rather than the ordinary
     * 按住说话 button. It only changes how the receiver treats it — auto-play
     * and a 「对讲」 badge — so the engine maps it to [MsgKind.VOICE_PTT] instead
     * of guessing from the file name.
     */
    val ptt: Boolean = false
) {
    fun toAttachment(transferId: String, kind: MsgKind): Attachment = Attachment(
        transferId = transferId,
        kind = kind,
        fileName = name,
        mime = mime,
        size = size,
        sha256 = sha256,
        width = width,
        height = height,
        durationMs = durationMs,
        localPath = filePath,
        thumbPath = thumbPath,
        thumbB64 = thumbB64,
        state = TransferState.IDLE
    )
}

data class TransferProgress(
    val transferId: String,
    val messageId: String,
    val convId: String,
    val outgoing: Boolean,
    val fileName: String,
    val total: Long,
    val done: Long,
    val state: TransferState,
    val error: String? = null,
    /**
     * Human-readable reason for a waiting state, e.g.
     * 「链路太差（2 KB/s），稍后自动重试」. The UI shows this verbatim rather
     * than inventing its own wording, so there is exactly one place that
     * decides what the user is told.
     */
    val waitNote: String? = null
) {
    val fraction: Float get() = if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Bluetooth / transport health, surfaced in the UI as a status pill. */
enum class LinkState { OFF, UNAUTHORIZED, READY, SCANNING, CONNECTING, CONNECTED, ERROR }

data class LinkStatus(
    val state: LinkState = LinkState.OFF,
    val message: String = "",
    val adapterName: String = "",
    val connections: Int = 0
)

fun newId(): String = UUID.randomUUID().toString()

fun seedOf(vararg parts: Any?): Int {
    var h = 7
    for (p in parts) h = h * 31 + (p?.hashCode() ?: 0)
    return h
}

/** Deterministic two-colour gradient avatar derived from the seed. */
fun avatarColors(seed: Int): Pair<Long, Long> {
    val hue = ((seed % 360) + 360) % 360
    val hue2 = (hue + 48) % 360
    return hslToArgb(hue.toFloat(), 0.72f, 0.58f) to hslToArgb(hue2.toFloat(), 0.68f, 0.44f)
}

fun hslToArgb(h: Float, s: Float, l: Float): Long {
    val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    val r = ((r1 + m) * 255f).toInt().coerceIn(0, 255)
    val g = ((g1 + m) * 255f).toInt().coerceIn(0, 255)
    val b = ((b1 + m) * 255f).toInt().coerceIn(0, 255)
    return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

object TimeFmt {
    fun clock(ts: Long): String {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = ts
        return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    fun listStamp(ts: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay) return clock(ts)
        val diff = now.timeInMillis - ts
        // "Yesterday" has to mean the previous calendar day, not "less than 48
        // hours ago". The old duration check labelled anything from one to two
        // days back as 昨天, and — because a future timestamp has a negative
        // diff, which is also `< 48h` — a message from a peer whose clock runs
        // ahead was labelled 昨天 too.
        if (diff < 0) return clock(ts)
        if (calendarDayDiff(now.timeInMillis, ts) == 1L) return "昨天"
        if (diff < 7L * 24 * 3600_000L) {
            val names = arrayOf("", "周日", "周一", "周二", "周三", "周四", "周五", "周六")
            return names[then.get(java.util.Calendar.DAY_OF_WEEK)]
        }
        return "%d/%d".format(then.get(java.util.Calendar.MONTH) + 1, then.get(java.util.Calendar.DAY_OF_MONTH))
    }

    /** Whole calendar days between two instants, in the device's time zone. */
    private fun calendarDayDiff(now: Long, then: Long): Long {
        val a = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val b = java.util.Calendar.getInstance().apply { timeInMillis = then }
        val dayA = a.get(java.util.Calendar.YEAR) * 366L + a.get(java.util.Calendar.DAY_OF_YEAR)
        val dayB = b.get(java.util.Calendar.YEAR) * 366L + b.get(java.util.Calendar.DAY_OF_YEAR)
        return dayA - dayB
    }

    /** True when both instants fall on the same calendar day. */
    private fun sameCalendarDay(a: Long, b: Long): Boolean = calendarDayDiff(a, b) == 0L

    /**
     * 「月/日 时:分」 for a date, 「时:分」 for today.
     *
     * The old body was `listStamp(ts) + " " + clock(ts)`, and `listStamp` already
     * returns `clock(ts)` for today — so every message sent today rendered as
     * 「00:10 00:10」. `MessageBubble` had grown a private workaround for it;
     * this is the fix.
     */
    fun chatStamp(ts: Long): String {
        val now = System.currentTimeMillis()
        return if (sameCalendarDay(now, ts)) clock(ts) else listStamp(ts) + " " + clock(ts)
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    fun duration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
