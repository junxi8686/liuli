package com.liuli.btchat.ui.components

import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liuli.btchat.core.Attachment
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.Quote
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.core.TransferState
import com.liuli.btchat.core.seedOf
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii
import java.io.File

/**
 * 微信式消息气泡（含引用 / 撤回 / 收藏 / 多选）。
 *
 * 性能约定（延续 task-7）：**整个文件里没有任何 `glassSurface`**。
 * 气泡、头像、时间胶囊、传输卡片全部平涂 —— 每多一个玻璃面就多一个 GraphicsLayer +
 * 一次 RenderEffect，一屏十几条消息直接拖垮滚动。整页只在顶栏、输入栏、弹层留玻璃。
 *
 * 渲染规则：
 *  * 我方 = 微信绿竖向渐变 + 深色字 + 右上小尖角；对方 = 纯白 + 发丝边 + 左上小尖角；
 *  * `quote != null` → 气泡内顶部一条窄引用块（左侧 2dp 绿线 + 发送者 + 单行预览）；
 *  * `recalled` → 不再画气泡，改成一行灰底斜体小字（你/对方/XXX 撤回了一条消息）；
 *  * `starred` → 时间戳旁一颗琥珀小星星；
 *  * 多选模式 → 行首出现绿色圆形勾选，点整行即切换选中，长按菜单让位。
 *
 * @param message 要渲染的消息。
 * @param isGroup 群聊时对方消息带头像与昵称。
 * @param showSenderName 是否显示发送者昵称（同一人连续发言只在第一条显示）。
 * @param selectionMode 多选模式：整行可点、显示勾选圈。
 * @param selected 当前是否被勾选。
 * @param highlighted 从搜索结果跳过来时的短暂高亮。
 * @param onLongPress 长按气泡（复制/引用/转发/收藏/多选/撤回/删除）。
 * @param onOpenImage 点开图片（全屏预览）。
 * @param onOpenVideo 点开视频（跳播放页）。
 * @param onRetry 失败重发。
 * @param onToggleSelect 多选模式下切换选中。
 * @param onOpenContact 点对方头像 → 对方主页（deviceId）。
 */
@Composable
fun MessageBubble(
    message: Message,
    isGroup: Boolean,
    showSenderName: Boolean,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    highlighted: Boolean = false,
    onLongPress: (Message) -> Unit = {},
    onOpenImage: (Message) -> Unit = {},
    onOpenVideo: (Message) -> Unit = {},
    onRetry: (Message) -> Unit = {},
    onAccept: (Message) -> Unit = {},
    onReject: (Message) -> Unit = {},
    onCancelTransfer: (Message) -> Unit = {},
    onToggleSelect: (Message) -> Unit = {},
    onOpenContact: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    when {
        message.recalled -> RecalledLine(message, isGroup, modifier)
        message.kind == MsgKind.SYSTEM -> SystemLine(message.text, modifier)
        else -> BubbleRow(
            message = message,
            isGroup = isGroup,
            showSenderName = showSenderName,
            selectionMode = selectionMode,
            selected = selected,
            highlighted = highlighted,
            onLongPress = onLongPress,
            onOpenImage = onOpenImage,
            onOpenVideo = onOpenVideo,
            onRetry = onRetry,
            onAccept = onAccept,
            onReject = onReject,
            onCancelTransfer = onCancelTransfer,
            onToggleSelect = onToggleSelect,
            onOpenContact = onOpenContact,
            modifier = modifier
        )
    }
}

@Composable
private fun BubbleRow(
    message: Message,
    isGroup: Boolean,
    showSenderName: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    highlighted: Boolean,
    onLongPress: (Message) -> Unit,
    onOpenImage: (Message) -> Unit,
    onOpenVideo: (Message) -> Unit,
    onRetry: (Message) -> Unit,
    onAccept: (Message) -> Unit,
    onReject: (Message) -> Unit,
    onCancelTransfer: (Message) -> Unit,
    onToggleSelect: (Message) -> Unit,
    onOpenContact: (String) -> Unit,
    modifier: Modifier
) {
    val outgoing = message.outgoing
    // 旧数据里昵称可能被冻结成「我」，这种情况不显示。
    val sender = message.senderName.takeIf { it.isNotBlank() && it != "我" }
    val tap = { if (selectionMode) onToggleSelect(message) else Unit }
    val longPress = { if (!selectionMode) onLongPress(message) else Unit }

    Row(
        modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.background(HighlightTint) else Modifier)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = selectionMode,
                onClick = { onToggleSelect(message) }
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (selectionMode) {
            SelectDot(
                selected = selected,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Spacer(Modifier.width(10.dp))
        }

        if (!outgoing && isGroup) {
            // 列表里不用玻璃光泽：GlassAvatar 的玻璃会把渐变核心盖成壁纸色块。
            GlassAvatar(
                name = sender ?: message.senderName,
                seed = seedOf(message.senderId, message.senderName),
                size = 38.dp,
                glassSheen = false,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !selectionMode && message.senderId.isNotBlank(),
                    onClick = { onOpenContact(message.senderId) }
                )
            )
            Spacer(Modifier.width(9.dp))
        }

        Column(
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start
        ) {
            if (isGroup && !outgoing && showSenderName && sender != null) {
                Text(
                    sender,
                    color = LiuliColors.TextTertiary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
                )
            }

            when (message.kind) {
                MsgKind.TEXT -> TextBubble(message, tap, longPress)
                MsgKind.IMAGE -> MediaBubble(
                    message = message,
                    video = false,
                    tap = { if (selectionMode) onToggleSelect(message) else onOpenImage(message) },
                    longPress = longPress,
                    onRetry = onRetry,
                    onAccept = onAccept,
                    onReject = onReject,
                    onCancelTransfer = onCancelTransfer
                )

                MsgKind.VIDEO -> MediaBubble(
                    message = message,
                    video = true,
                    tap = { if (selectionMode) onToggleSelect(message) else onOpenVideo(message) },
                    longPress = longPress,
                    onRetry = onRetry,
                    onAccept = onAccept,
                    onReject = onReject,
                    onCancelTransfer = onCancelTransfer
                )

                // 语音：气泡自己管播放/暂停（文件没到会就地提示），多选时交给选择逻辑。
                MsgKind.VOICE -> VoiceBubble(
                    message = message,
                    selectionMode = selectionMode,
                    tap = { onToggleSelect(message) },
                    longPress = longPress,
                    onRetry = onRetry,
                    onAccept = onAccept,
                    onReject = onReject,
                    onCancelTransfer = onCancelTransfer
                )

                // 对讲机：同一条音频管线，差别在接收端自动播放 + 「对讲」标记。
                // VoiceBubble 拿到 ptt 标记后会自己画那个标记，这里不重复分支。
                MsgKind.VOICE_PTT -> VoiceBubble(
                    message = message,
                    selectionMode = selectionMode,
                    tap = { onToggleSelect(message) },
                    longPress = longPress,
                    onRetry = onRetry,
                    onAccept = onAccept,
                    onReject = onReject,
                    onCancelTransfer = onCancelTransfer
                )

                MsgKind.FILE -> FileBubble(
                    message = message,
                    tap = tap,
                    longPress = longPress,
                    onRetry = onRetry,
                    onAccept = onAccept,
                    onReject = onReject,
                    onCancelTransfer = onCancelTransfer
                )

                MsgKind.SYSTEM -> Unit
            }

            // 双方都要显示时间（用户反馈：只能看自己发的时间没有意义）。
            // 选的是「每条气泡下方都带一个 10.5sp 的时刻」，再叠加 5 分钟的分组胶囊，
            // 这样两边完全一致。
            if (outgoing) {
                StatusLine(message, onRetry)
            } else {
                IncomingMetaLine(message)
            }
        }
    }
}

// ------------------------------------------------------------------ 文本气泡

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextBubble(
    message: Message,
    tap: () -> Unit,
    longPress: () -> Unit
) {
    val shape = bubbleShape(message.outgoing)
    Box(
        Modifier
            .widthIn(max = bubbleMaxWidth())
            .bubbleBackground(message.outgoing, shape)
            .combinedClickable(onClick = tap, onLongClick = longPress)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column {
            message.quote?.let { QuoteBlock(it, message.outgoing) }
            Text(
                message.text,
                color = if (message.outgoing) LiuliColors.BubbleMineText else LiuliColors.BubbleTheirsText,
                style = BubbleTextStyle
            )
        }
    }
}

// ------------------------------------------------------------- 图片 / 视频气泡

@Composable
private fun MediaBubble(
    message: Message,
    video: Boolean,
    tap: () -> Unit,
    longPress: () -> Unit,
    onRetry: (Message) -> Unit,
    onAccept: (Message) -> Unit,
    onReject: (Message) -> Unit,
    onCancelTransfer: (Message) -> Unit
) {
    val att = message.attachment
    if (att != null) {
        MediaBubbleBody(
            message = message,
            att = att,
            video = video,
            tap = tap,
            longPress = longPress,
            onRetry = onRetry,
            onAccept = onAccept,
            onReject = onReject,
            onCancelTransfer = onCancelTransfer
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaBubbleBody(
    message: Message,
    att: Attachment,
    video: Boolean,
    tap: () -> Unit,
    longPress: () -> Unit,
    onRetry: (Message) -> Unit,
    onAccept: (Message) -> Unit,
    onReject: (Message) -> Unit,
    onCancelTransfer: (Message) -> Unit
) {
    val thumb = rememberAttachmentThumb(att)
    val model = rememberThumbModel(att, thumb)
    val shape = RoundedCornerShape(Radii.thumb)
    val ratio = if (att.width > 0 && att.height > 0) {
        (att.width.toFloat() / att.height.toFloat()).coerceIn(0.62f, 1.5f)
    } else {
        4f / 3f
    }
    // 最长边 ≈200dp，另一条边按比例收，微信的图不会顶满屏幕。
    val boxWidth = if (ratio >= 1f) 200.dp else (200.dp * ratio)

    Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
        message.quote?.let {
            QuoteChip(
                quote = it,
                modifier = Modifier.widthIn(max = bubbleMaxWidth())
            )
            Spacer(Modifier.height(4.dp))
        }

        Box(
            Modifier
                .width(boxWidth)
                .aspectRatio(ratio)
                .clip(shape)
                .background(LiuliColors.SurfaceSunken)
                .combinedClickable(onClick = tap, onLongClick = longPress)
        ) {
            if (model != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(model)
                        .size(MEDIA_DECODE_PX)
                        .crossfade(false)
                        .build(),
                    contentDescription = if (video) "视频" else "图片",
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(LiuliColors.SurfaceSunken),
                    error = ColorPainter(LiuliColors.SurfaceSunken),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (video) LiuliIcons.Video else LiuliIcons.Image,
                        contentDescription = null,
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            if (video) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.42f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        LiuliIcons.Play,
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                if (att.durationMs > 0L) {
                    Text(
                        TimeFmt.duration(att.durationMs),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.60f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }

            if (att.state == TransferState.TRANSFERRING) {
                TransferVeil(att)
            }
        }

        TransferCard(
            attachment = att,
            outgoing = message.outgoing,
            onAccept = { onAccept(message) },
            onReject = { onReject(message) },
            onCancel = { onCancelTransfer(message) },
            onRetry = { onRetry(message) }
        )
    }
}

/** 传输中盖在缩略图上的一层薄纱 + 线性进度。 */
@Composable
private fun TransferVeil(att: Attachment) {
    val fraction = if (att.size > 0L) {
        (att.transferred.toFloat() / att.size.toFloat()).coerceIn(0f, 1f)
    } else {
        att.progress.coerceIn(0f, 1f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.30f))
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.30f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .background(LiuliColors.Accent)
            )
        }
    }
}

// ------------------------------------------------------------------ 文件气泡

@Composable
private fun FileBubble(
    message: Message,
    tap: () -> Unit,
    longPress: () -> Unit,
    onRetry: (Message) -> Unit,
    onAccept: (Message) -> Unit,
    onReject: (Message) -> Unit,
    onCancelTransfer: (Message) -> Unit
) {
    val att = message.attachment
    if (att != null) {
        FileBubbleBody(
            message = message,
            att = att,
            tap = tap,
            longPress = longPress,
            onRetry = onRetry,
            onAccept = onAccept,
            onReject = onReject,
            onCancelTransfer = onCancelTransfer
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileBubbleBody(
    message: Message,
    att: Attachment,
    tap: () -> Unit,
    longPress: () -> Unit,
    onRetry: (Message) -> Unit,
    onAccept: (Message) -> Unit,
    onReject: (Message) -> Unit,
    onCancelTransfer: (Message) -> Unit
) {
    val outgoing = message.outgoing
    val shape = bubbleShape(outgoing)

    Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Row(
            Modifier
                .widthIn(max = bubbleMaxWidth())
                .bubbleBackground(outgoing, shape)
                .combinedClickable(onClick = tap, onLongClick = longPress)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(Radii.thumb))
                    .background(
                        if (outgoing) Color.White.copy(alpha = 0.45f) else LiuliColors.SurfaceSunken
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    LiuliIcons.File,
                    contentDescription = null,
                    tint = if (outgoing) LiuliColors.BubbleMineText else LiuliColors.Accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                message.quote?.let { QuoteBlock(it, outgoing) }
                Text(
                    att.fileName.ifBlank { "未命名文件" },
                    color = if (outgoing) LiuliColors.BubbleMineText else LiuliColors.BubbleTheirsText,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    TimeFmt.size(att.size),
                    color = if (outgoing) {
                        LiuliColors.BubbleMineText.copy(alpha = 0.6f)
                    } else {
                        LiuliColors.TextTertiary
                    },
                    fontSize = 11.5.sp
                )
            }
            Icon(
                LiuliIcons.Download,
                contentDescription = null,
                tint = when {
                    message.state == MsgState.FAILED -> LiuliColors.Danger
                    outgoing -> LiuliColors.BubbleMineText.copy(alpha = 0.65f)
                    else -> LiuliColors.TextTertiary
                },
                modifier = Modifier.size(17.dp)
            )
        }

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

// ------------------------------------------------------------ 引用 / 撤回 / 勾选

/** 气泡内部的引用块：2dp 绿色竖线 + 发送者 + 单行预览。 */
@Composable
private fun QuoteBlock(quote: Quote, outgoing: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x14000000))
            .padding(6.dp)
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(30.dp)
                .background(LiuliColors.Accent, RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                quote.senderName.takeIf { it.isNotBlank() } ?: "对方",
                color = if (outgoing) {
                    LiuliColors.BubbleMineText.copy(alpha = 0.75f)
                } else {
                    LiuliColors.TextSecondary
                },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                quote.preview,
                color = if (outgoing) {
                    LiuliColors.BubbleMineText.copy(alpha = 0.85f)
                } else {
                    LiuliColors.TextSecondary
                },
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 图片/视频气泡外面的引用块（媒体气泡没有底，引用就贴在它上面）。 */
@Composable
private fun QuoteChip(quote: Quote, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x14000000))
            .padding(6.dp)
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(28.dp)
                .background(LiuliColors.Accent, RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                quote.senderName.takeIf { it.isNotBlank() } ?: "对方",
                color = LiuliColors.TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                quote.preview,
                color = LiuliColors.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 撤回墓碑：一行灰底斜体小字，不再渲染原内容。 */
@Composable
private fun RecalledLine(message: Message, isGroup: Boolean, modifier: Modifier = Modifier) {
    val label = when {
        message.outgoing -> "你撤回了一条消息"
        isGroup && message.senderName.isNotBlank() && message.senderName != "我" ->
            "${message.senderName} 撤回了一条消息"

        else -> "对方撤回了一条消息"
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = LiuliColors.TextTertiary,
            fontSize = 12.sp,
            fontStyle = FontStyle.Italic,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x80DADDE1))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** 多选模式的圆形勾选。 */
@Composable
private fun SelectDot(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) LiuliColors.Accent else Color.Transparent)
            .border(
                1.5.dp,
                if (selected) LiuliColors.Accent else LiuliColors.TextTertiary,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                LiuliIcons.Check,
                contentDescription = "已选中",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// -------------------------------------------------------------------- 状态行

/**
 * 对方消息下方的一行小字：收藏星标 + 送达时刻。
 *
 * 和 [StatusLine] 用同样的字号与间距，保证双方视觉一致 —— 用户明确要求「对方的消息
 * 也要有时间」。
 */
@Composable
private fun IncomingMetaLine(message: Message) {
    Row(
        Modifier.padding(top = 3.dp, start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (message.starred) {
            Icon(
                LiuliIcons.Star,
                contentDescription = "已收藏",
                tint = LiuliColors.Amber,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            TimeFmt.clock(message.sentAt),
            color = LiuliColors.TextTertiary,
            fontSize = 10.5.sp
        )
    }
}

/** 气泡外侧下方的一行小字：收藏星标 + 时间 + 送达状态。 */
@Composable
private fun StatusLine(message: Message, onRetry: (Message) -> Unit) {
    Row(
        Modifier.padding(top = 3.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (message.starred) {
            Icon(
                LiuliIcons.Star,
                contentDescription = "已收藏",
                tint = LiuliColors.Amber,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            TimeFmt.clock(message.sentAt),
            color = LiuliColors.TextTertiary,
            fontSize = 10.5.sp
        )
        when (message.state) {
            MsgState.SENDING -> CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                color = LiuliColors.TextTertiary,
                strokeWidth = 1.3.dp
            )

            MsgState.SENT -> Icon(
                LiuliIcons.Check,
                contentDescription = "已发送",
                tint = LiuliColors.TextTertiary,
                modifier = Modifier.size(13.dp)
            )

            MsgState.DELIVERED -> Icon(
                LiuliIcons.Checked,
                contentDescription = "已送达",
                tint = LiuliColors.TextTertiary,
                modifier = Modifier.size(13.dp)
            )

            MsgState.READ -> Icon(
                LiuliIcons.Checked,
                contentDescription = "已读",
                tint = LiuliColors.Accent,
                modifier = Modifier.size(13.dp)
            )

            MsgState.FAILED -> {
                Icon(
                    LiuliIcons.Error,
                    contentDescription = "发送失败",
                    tint = LiuliColors.Danger,
                    modifier = Modifier
                        .size(13.dp)
                        .clickable { onRetry(message) }
                )
                Text(
                    "未送达，点击重试",
                    color = LiuliColors.Danger,
                    fontSize = 10.5.sp,
                    modifier = Modifier.clickable { onRetry(message) }
                )
            }

            MsgState.DRAFT -> Unit
        }
    }
}

// -------------------------------------------------------------------- 小工具

/** 系统提示（入群、改名…）：居中的浅灰小胶囊，不属于任何一方。 */
@Composable
private fun SystemLine(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = LiuliColors.TextTertiary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x80DADDE1))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/** 相邻消息间隔超过 5 分钟时插在中间的浅灰时间胶囊（平涂，不用玻璃）。 */
@Composable
fun ChatTimeDivider(stamp: Long, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            dividerLabel(stamp),
            color = LiuliColors.TextTertiary,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x99DADDE1))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 时间胶囊的文案：今天的消息只给「19:44」，不是今天的才补上日期，
 * 避免出现「19:44 19:44」这种重复（[TimeFmt.chatStamp] 对今天的消息就是这个结果）。
 */
private fun dividerLabel(ts: Long): String {
    val stamp = TimeFmt.listStamp(ts)
    val clock = TimeFmt.clock(ts)
    return if (stamp == clock) stamp else "$stamp $clock"
}

/**
 * 微信气泡：靠发送者那一侧的顶角收成小尖角（我方右上、对方左上），
 * 其余三个角是 [Radii.bubble]。
 */
fun bubbleShape(outgoing: Boolean): Shape = if (outgoing) {
    RoundedCornerShape(
        topStart = Radii.bubble,
        topEnd = Radii.bubbleTail,
        bottomEnd = Radii.bubble,
        bottomStart = Radii.bubble
    )
} else {
    RoundedCornerShape(
        topStart = Radii.bubbleTail,
        topEnd = Radii.bubble,
        bottomEnd = Radii.bubble,
        bottomStart = Radii.bubble
    )
}

/** 气泡最大宽度：屏宽的 72%（微信差不多就是这个比例）。 */
@Composable
fun bubbleMaxWidth() = (LocalConfiguration.current.screenWidthDp * 0.72f).dp

/**
 * 气泡底：我方微信绿竖向渐变，对方纯白 + 一根发丝边。
 * 全程只画背景，不建图层、不做模糊 —— 这是聊天页流畅的前提。
 */
@Composable
fun Modifier.bubbleBackground(outgoing: Boolean, shape: Shape): Modifier = if (outgoing) {
    background(
        Brush.verticalGradient(
            listOf(LiuliColors.BubbleMineTop, LiuliColors.BubbleMineBottom)
        ),
        shape
    )
} else {
    background(LiuliColors.BubbleTheirs, shape)
        .border(0.6.dp, LiuliColors.Separator, shape)
}

/** 从搜索结果跳过来时，给目标消息一层短暂的琥珀高亮。 */
private val HighlightTint = Color(0x33FFC46B)

private val BubbleTextStyle = TextStyle(
    fontSize = 16.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.Normal
)

/** 缩略图解码上限：气泡最大边 200dp，600dpi 屏幕上约 750px，取 640 足够。 */
private const val MEDIA_DECODE_PX = 640

/**
 * 缩略图文件：先问 [com.liuli.btchat.core.MediaVault.thumbnail]，它自己会按
 * 「已有缩略图 → 内联 Base64 → 现解一张」的顺序找，全部在 IO 线程上完成。
 * 每个附件只在 key 变化时查一次，滚动时不会重复做 IO。
 */
@Composable
fun rememberAttachmentThumb(att: Attachment): File? {
    var file by remember(att.transferId, att.thumbPath, att.localPath) { mutableStateOf<File?>(null) }
    LaunchedEffect(att.transferId, att.thumbPath, att.localPath) {
        file = runCatching { Svc.media.thumbnail(att) }.getOrNull()
    }
    return file
}

/** Coil 的 model：优先磁盘缩略图，其次随 OFFER 一起到的内联 Base64 预览。 */
@Composable
private fun rememberThumbModel(att: Attachment, file: File?): Any? = if (file != null) {
    file
} else {
    remember(att.thumbB64) {
        att.thumbB64
            ?.takeIf { it.isNotBlank() }
            ?.let { b64 -> runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() }
    }
}

// ------------------------------------------------------------------ 全屏预览

/**
 * 图片全屏预览：纯黑底 + 顶部关闭 + 底部保存（按微信的做法，这里不用玻璃）。
 *
 * @param message 正在预览的消息，`null` 表示不显示。
 */
@Composable
fun ImagePreviewOverlay(
    message: Message?,
    onDismiss: () -> Unit,
    onSave: (Message) -> Unit,
    modifier: Modifier = Modifier
) {
    val att = message?.attachment
    if (message != null && att != null) {
        ImagePreviewBody(message, att, onDismiss, onSave, modifier)
    }
}

@Composable
private fun ImagePreviewBody(
    message: Message,
    att: Attachment,
    onDismiss: () -> Unit,
    onSave: (Message) -> Unit,
    modifier: Modifier = Modifier
) {
    val thumb = rememberAttachmentThumb(att)
    val full = remember(att.localPath) {
        att.localPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }
    }
    val model = full ?: thumb

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        if (model != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(model).crossfade(false).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 64.dp)
            )
        } else {
            Text(
                "图片还在传输中…",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .size(38.dp)
                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                LiuliIcons.Close,
                contentDescription = "关闭",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Text(
            "保存到相册",
            color = Color.White,
            fontSize = 15.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(LiuliColors.Accent)
                .clickable { onSave(message) }
                .padding(horizontal = 24.dp, vertical = 10.dp)
        )
    }
}
