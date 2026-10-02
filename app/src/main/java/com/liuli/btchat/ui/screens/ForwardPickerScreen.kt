package com.liuli.btchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import kotlinx.coroutines.delay

/**
 * 转发选择器（二级页面）。
 *
 * 列出所有会话，点一行就把 [messageId] 那条消息转发过去
 * （[com.liuli.btchat.core.ChatEngine.forward] 文本/图片/视频都能转），
 * 成功后提示「已转发」并回调 [onDone] 让上层回退。
 *
 * 行一律平涂：性能红线是「列表行不许用 glassSurface」。
 *
 * @param messageId 要转发的消息。
 * @param onBack 返回聊天页。
 * @param onDone 转发成功（或用户看完提示）后回退。
 */
@Composable
fun ForwardPickerScreen(
    messageId: String,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val revision by Svc.store.revision.collectAsState()
    val conversations = remember(revision) {
        Svc.store.conversations().sortedByDescending { it.lastMessageAt }
    }
    val source = remember(revision, messageId) { Svc.store.message(messageId) }
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val safeBottom = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }

    var notice by remember { mutableStateOf<String?>(null) }
    var forwarded by remember { mutableStateOf(false) }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(1100)
            notice = null
            if (forwarded) onDone()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(LiuliColors.Bg)
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(LiuliColors.Surface)
                    .padding(top = statusTop + 6.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            LiuliIcons.Back,
                            contentDescription = "返回",
                            tint = LiuliColors.TextPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "转发给",
                            color = LiuliColors.TextPrimary,
                            fontSize = 16.sp
                        )
                        if (source != null) {
                            Text(
                                source.preview.take(40),
                                color = LiuliColors.TextTertiary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.6.dp)
                    .background(LiuliColors.Separator)
            )

            if (conversations.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        LiuliIcons.Chats,
                        contentDescription = null,
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        "还没有别的会话可以转发",
                        color = LiuliColors.TextSecondary,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = safeBottom + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        val last = remember(revision, conv.id) { Svc.store.latestMessage(conv.id) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(LiuliColors.Surface)
                                .clickable {
                                    val sent = runCatching {
                                        Svc.engine.forward(messageId, conv.id)
                                    }.getOrNull()
                                    forward(sent, conv.title) { text, ok ->
                                        notice = text
                                        forwarded = ok
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassAvatar(
                                name = conv.title,
                                seed = conv.avatarSeed,
                                size = 42.dp,
                                glassSheen = false
                            )
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(
                                    conv.title.ifBlank { "会话" },
                                    color = LiuliColors.TextPrimary,
                                    fontSize = 15.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    last?.preview ?: if (conv.kind == ConvKind.GROUP) "群聊" else "单聊",
                                    color = LiuliColors.TextTertiary,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Text(
                                last?.let { TimeFmt.listStamp(it.sentAt) } ?: "",
                                color = LiuliColors.TextTertiary,
                                fontSize = 11.5.sp
                            )
                        }
                    }
                }
            }
        }

        notice?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 13.5.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusTop + 68.dp)
                    .background(Color(0xE6000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/** 转发结果 → 一句提示；成功才在提示后回退。 */
private inline fun forward(
    sent: com.liuli.btchat.core.Message?,
    target: String,
    report: (String, Boolean) -> Unit
) {
    if (sent != null) {
        report("已转发到「${target.ifBlank { "会话" }}」", true)
    } else {
        report("转发失败：对方会话可能不可用", false)
    }
}
