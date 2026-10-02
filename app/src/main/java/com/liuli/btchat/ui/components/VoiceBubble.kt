package com.liuli.btchat.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.TransferState
import com.liuli.btchat.media.VoicePlayer
import com.liuli.btchat.media.Waveform
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import java.io.File
import kotlinx.coroutines.delay

/**
 * 语音消息气泡（微信式）。
 *
 * 视觉：
 *  * 我方 = 微信绿渐变 + 深色字；对方 = 纯白 + 发丝边（复用 [bubbleBackground]）；
 *  * 左侧播放/暂停图标，中间一列静态波形（[Waveform.peaksOf] 真解码，解不开才退化），
 *    右侧「N"」时长；
 *  * **宽度随语音时长增长**：`84dp + (秒/60)*120dp`，封顶屏宽 60%；
 *  * 播放中波形按播放进度染色：播过的部分用强调色，没播的是灰。
 *
 * 行为：
 *  * 点一下播放/暂停（[VoicePlayer.toggle]，全局同一时刻只播一条）；
 *  * 文件还没到（`localPath == null`）点一下会就地冒一句「语音还在传输中…」，不静默；
 *  * 传输中/待接收照常显示 [TransferCard]（接收/拒绝/进度/重发）；
 *  * 长按交给现有的消息操作菜单。
 *
 * 性能：整个文件平涂，没有任何 `glassSurface`（消息列表的红线）。
 *
 * @param message 语音消息（`attachment.durationMs` 是时长）。
 * @param selectionMode 多选模式：点击变成勾选，不播放。
 * @param tap 多选模式下的勾选动作。
 * @param longPress 长按（弹出操作菜单）。
 */
@Composable
fun VoiceBubble(
    message: Message,
    selectionMode: Boolean = false,
    tap: () -> Unit = {},
    longPress: () -> Unit = {},
    onRetry: (Message) -> Unit = {},
    onAccept: (Message) -> Unit = {},
    onReject: (Message) -> Unit = {},
    onCancelTransfer: (Message) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val att = message.attachment
    if (att != null) {
        VoiceBubbleBody(
            message = message,
            att = att,
            selectionMode = selectionMode,
            tap = tap,
            longPress = longPress,
            onRetry = onRetry,
            onAccept = onAccept,
            onReject = onReject,
            onCancelTransfer = onCancelTransfer,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceBubbleBody(
    message: Message,
    att: Attachment,
    selectionMode: Boolean,
    tap: () -> Unit,
    longPress: () -> Unit,
    onRetry: (Message) -> Unit,
    onAccept: (Message) -> Unit,
    onReject: (Message) -> Unit,
    onCancelTransfer: (Message) -> Unit,
    modifier: Modifier
) {
    val outgoing = message.outgoing
    val shape = bubbleShape(outgoing)
    val path = att.localPath

    // 颜色先取好：下面有非 composable 的 lambda 会用到。
    val mineText = LiuliColors.BubbleMineText
    val accent = LiuliColors.Accent
    val theirsText = LiuliColors.BubbleTheirsText
    val secondary = LiuliColors.TextSecondary
    val playedColor = if (outgoing) mineText else accent
    // 未播放的波形要跟**气泡自己的**字色同源，不能跟页面字色同源：
    // TextTertiary 在深色下本身就是深灰，再乘 0.45 叠在 #25262B 的气泡上只有
    // 1.6:1，基本看不见。跟着气泡字色走，深浅两种主题都成立。
    val restColor = if (outgoing) mineText.copy(alpha = 0.35f) else theirsText.copy(alpha = 0.30f)

    val playingPath by VoicePlayer.playing.collectAsState()
    val isPlaying = path != null && playingPath == path

    // 播放进度：VoicePlayer 暴露的是 getter，播放期间按 80ms 采样一次即可。
    var progress by remember(path) { mutableStateOf(0f) }
    LaunchedEffect(isPlaying, path) {
        if (isPlaying) {
            while (true) {
                val total = VoicePlayer.durationMs
                val now = VoicePlayer.positionMs
                progress = if (total > 0L) (now.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                delay(80)
            }
        } else {
            progress = 0f
        }
    }

    // 波形：先同步拿缓存（滚动时不闪），没有就异步解码；首帧用装饰波形垫一下。
    val file = remember(path) { path?.let { File(it) } }
    var peaks by remember(path) { mutableStateOf(path?.let { Waveform.cachedPeaks(it, Bars) }) }
    LaunchedEffect(path) {
        if (path != null) {
            peaks = runCatching { Waveform.peaks(path, Bars) }.getOrNull()
        }
    }
    val placeholder = remember(file) {
        file?.let { runCatching { Waveform.decorativePeaks(it, Bars) }.getOrNull() }
    }
    val bars = peaks ?: placeholder ?: FloatArray(Bars) { 0.22f }

    // 播放不了的原因：**按传输状态给准确文案**，不再一律「还在传输中」。
    // null = 文件已经在本地，可以正常播。
    val blocked = voiceBlockedReason(att)
    val ptt = message.kind == MsgKind.VOICE_PTT

    // 气泡宽度：84dp 起步，60 秒到 204dp，封顶屏宽 60%。
    val seconds = ((att.durationMs + 999L) / 1000L).toInt().coerceIn(1, 60)
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.6f).dp
    val width: Dp = minOf(84.dp + 120.dp * (seconds / 60f), maxWidth)

    Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        // 「对讲」标记：和对讲机发出来的语音区分开
        if (ptt) {
            Text(
                "对讲",
                color = if (outgoing) mineText.copy(alpha = 0.85f) else accent,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(bottom = 3.dp, start = 2.dp, end = 2.dp)
            )
        }
        Row(
            modifier
                .width(width)
                .bubbleBackground(outgoing, shape)
                .combinedClickable(
                    onClick = {
                        when {
                            selectionMode -> tap()
                            path != null -> VoicePlayer.toggle(path)
                            // 没文件时按状态给动作：待接收→接收，失败→重试，其余状态
                            // 上面那行 blocked 文案已经说清楚了，点了不做多余的事。
                            att.state == TransferState.OFFERED ||
                                att.state == TransferState.IDLE -> onAccept(message)

                            att.state == TransferState.FAILED -> onRetry(message)
                            else -> Unit
                        }
                    },
                    onLongClick = { if (!selectionMode) longPress() }
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isPlaying) LiuliIcons.Pause else LiuliIcons.Play,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = playedColor,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            VoiceWaveform(
                peaks = bars,
                progress = progress,
                played = playedColor,
                rest = restColor,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$seconds\"",
                color = if (outgoing) mineText.copy(alpha = 0.75f) else secondary,
                fontSize = 13.sp
            )
        }

        // 状态提示：准确说明为什么播不了，可点的时候就是可操作的
        if (blocked != null) {
            Text(
                blocked,
                color = Color.White,
                fontSize = 12.5.sp,
                modifier = Modifier
                    .padding(top = 5.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(4.dp))
                    .clickable {
                        when {
                            att.state == TransferState.OFFERED ||
                                att.state == TransferState.IDLE -> onAccept(message)

                            att.state == TransferState.FAILED -> onRetry(message)
                            else -> Unit
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }

        // 收到但还没接收 / 正在传 / 失败：走同一张传输卡片。
        TransferCard(
            attachment = att,
            outgoing = outgoing,
            onAccept = { onAccept(message) },
            onReject = { onReject(message) },
            onCancel = { onCancelTransfer(message) },
            onRetry = { onRetry(message) }
        )
    }
}

/** 静态波形：播过的竖条用强调色，没播的用灰色。 */
@Composable
private fun VoiceWaveform(
    peaks: FloatArray,
    progress: Float,
    played: Color,
    rest: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        peaks.forEachIndexed { index, value ->
            val playedBar = progress > 0f && (index + 1).toFloat() / peaks.size.toFloat() <= progress
            Box(
                Modifier
                    .width(BarWidth)
                    .height((4f + value.coerceIn(0f, 1f) * 18f).dp)
                    .background(
                        if (playedBar) played else rest,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/** 波形条数（20~28 之间，微信大概这个密度）。 */
private const val Bars = 24

/**
 * 语音播不了时的准确说明；`null` = 本地有文件、可以播。
 *
 * 用户报过「已经取消接收了，点播放还提示语音还在传输中」—— 那是因为只看文件在不在。
 * 这里按 `Attachment.state` 分成四句不同的话，并且 `OFFERED`/`FAILED` 两句是可点的
 * （点了直接接收 / 重试），不做无用提示。
 */
private fun voiceBlockedReason(att: Attachment): String? = when {
    // 有文件就是能播，状态怎么写都不影响
    att.localPath?.let { File(it).isFile && File(it).length() > 0L } == true -> null

    att.state == TransferState.OFFERED || att.state == TransferState.IDLE ->
        "语音还没接收，点这里接收"

    att.state == TransferState.WAIT_ACCEPT -> "等待对方接收…"
    att.state == TransferState.TRANSFERRING -> "语音正在传输中…"
    att.state == TransferState.REJECTED -> "语音已取消接收（你拒绝了这条）"
    att.state == TransferState.CANCELLED -> "语音已取消接收"
    att.state == TransferState.FAILED -> "语音接收失败，点这里重试"
    att.state == TransferState.DONE -> "语音文件已不在本机（可能被清理）"
    else -> "语音不可用"
}

private val BarWidth = 2.5.dp
