package com.liuli.btchat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.core.TransferState
import com.liuli.btchat.media.MediaEstimates
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii

/**
 * 附件消息下方的「传输状态」区。
 *
 * 微信式平涂，没有任何玻璃（列表里一屏十几条，玻璃会直接拖垮滚动）：
 *  * 等我们点头（`OFFERED` / 收到的 `IDLE`）—— 体积 + 预计耗时 + 「拒绝 / 接收」；
 *  * 等对方点头（`WAIT_ACCEPT`）—— 只能「取消」；
 *  * 正在跑（`TRANSFERRING`）—— 细进度条 + 已传/总量 + 剩余耗时，可中断；
 *  * 失败/被拒/取消 —— 一句说明，发送方可直接重发。
 *
 * 卡片本身没有底色，它总是长在气泡或缩略图下面，由调用方决定排布。
 *
 * @param attachment 消息附件（进度、状态、体积都从这里读）。
 * @param outgoing `true` 表示这条是"我"发出的，措辞与可用操作都不同。
 * @param onAccept 接收（落到 [com.liuli.btchat.bt.Engine.acceptTransfer]）。
 * @param onReject 拒绝。
 * @param onCancel 取消进行中的传输 / 放弃等待。
 * @param onRetry 失败后重发。
 */
@Composable
fun TransferCard(
    attachment: Attachment,
    outgoing: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = attachment.state
    // 收到 file_offer 但还没写库状态时按 OFFERED 处理，避免对面发来的文件没有「接收」按钮。
    val awaitingUs = !outgoing &&
        (state == TransferState.OFFERED || (state == TransferState.IDLE && attachment.localPath == null))

    Column(
        modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when {
            awaitingUs -> OfferRow(attachment, onAccept, onReject)
            state == TransferState.WAIT_ACCEPT -> WaitRow(outgoing, onCancel)
            state == TransferState.TRANSFERRING -> ProgressBlock(attachment, outgoing, onCancel)
            state == TransferState.FAILED -> FailedRow(outgoing, onRetry)
            state == TransferState.REJECTED ->
                Note(if (outgoing) "对方拒绝接收" else "已拒绝接收")

            state == TransferState.CANCELLED ->
                Note(if (outgoing) "已取消发送" else "已取消接收")

            state == TransferState.DONE -> Unit
        }
    }
}

// --------------------------------------------------------------------- 分支

@Composable
private fun OfferRow(att: Attachment, onAccept: () -> Unit, onReject: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${kindWord(att)} · ${TimeFmt.size(att.size)} · ${MediaEstimates.transferText(att.size)}",
            color = LiuliColors.TextTertiary,
            fontSize = 11.5.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatChip(onClick = onReject, filled = false) {
                Text("拒绝", color = LiuliColors.TextSecondary, fontSize = 13.sp)
            }
            FlatChip(onClick = onAccept, filled = true) {
                Icon(
                    LiuliIcons.Download,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.height(14.dp)
                )
                Text("接收", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun WaitRow(outgoing: Boolean, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (outgoing) "等待对方接收…" else "正在等待…",
            color = LiuliColors.TextTertiary,
            fontSize = 11.5.sp,
            modifier = Modifier.weight(1f)
        )
        FlatChip(onClick = onCancel, filled = false) {
            Text("取消", color = LiuliColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ProgressBlock(att: Attachment, outgoing: Boolean, onCancel: () -> Unit) {
    val total = if (att.size > 0L) att.size else att.transferred
    val done = att.transferred.coerceIn(0L, if (total > 0L) total else Long.MAX_VALUE)
    val fraction = if (total > 0L) {
        (done.toFloat() / total).coerceIn(0f, 1f)
    } else {
        att.progress.coerceIn(0f, 1f)
    }
    val animated by animateFloatAsState(
        targetValue = fraction,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f),
        label = "transferFraction"
    )

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (outgoing) "正在发送" else "正在接收"}${kindWord(att)}",
                color = LiuliColors.TextSecondary,
                fontSize = 11.5.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${TimeFmt.size(done)} / ${TimeFmt.size(total)}",
                color = LiuliColors.TextTertiary,
                fontSize = 11.sp
            )
        }
        TrackBar(animated)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                MediaEstimates.transferText((total - done).coerceAtLeast(0L)),
                color = LiuliColors.TextTertiary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            FlatChip(onClick = onCancel, filled = false) {
                Text("取消", color = LiuliColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FailedRow(outgoing: Boolean, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            LiuliIcons.Warning,
            contentDescription = null,
            tint = LiuliColors.Danger,
            modifier = Modifier.height(14.dp)
        )
        Text(
            if (outgoing) "传输未完成" else "接收失败",
            color = LiuliColors.Danger,
            fontSize = 11.5.sp,
            modifier = Modifier.weight(1f)
        )
        if (outgoing) {
            FlatChip(onClick = onRetry, filled = false) {
                Text("重发", color = LiuliColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = LiuliColors.TextTertiary,
        fontSize = 11.5.sp
    )
}

// ------------------------------------------------------------------- 零件

/**
 * 传输文案里的名词：语音说「语音」，图片说「图片」…
 * 之前进度行只写「正在接收」，用户分不清在收什么；现在按附件类型说清楚。
 */
private fun kindWord(att: Attachment): String = when (att.kind) {
    MsgKind.VOICE -> "语音"
    MsgKind.IMAGE -> "图片"
    MsgKind.VIDEO -> "视频"
    MsgKind.FILE -> "文件"
    else -> "内容"
}

/**
 * 平涂小按钮：`filled = true` 是微信绿实心（接收/发送），否则白底 + 发丝边的次级按钮。
 * 没有玻璃、没有阴影，点按只走一次普通的 ripple。
 */
@Composable
private fun FlatChip(
    onClick: () -> Unit,
    filled: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) LiuliColors.Accent else LiuliColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        content = content
    )
}

/** 细长的进度槽：浅灰底 + 微信绿前景。 */
@Composable
private fun TrackBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(Radii.pill))
            .background(LiuliColors.Separator)
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(LiuliColors.Accent, RoundedCornerShape(Radii.pill))
            )
        }
    }
}
