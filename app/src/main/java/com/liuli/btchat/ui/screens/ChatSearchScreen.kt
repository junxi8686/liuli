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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.core.seedOf
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii

/**
 * 会话内搜索（二级页面）。
 *
 * 微信的「查找聊天记录」是独立一页：进来就聚焦输入框，边打边搜，命中关键词高亮。
 * 数据直接用 [com.liuli.btchat.core.ChatStore.searchMessages]，再按当前会话过滤 ——
 * 搜索接口是全库级别的，二级页只关心这一条会话。
 *
 * @param convId 只在结果里保留这个会话的消息。
 * @param onBack 返回聊天信息页。
 * @param onOpenMessage 点结果 → 回到聊天页并定位高亮那条消息。
 */
@Composable
fun ChatSearchScreen(
    convId: String,
    onBack: () -> Unit,
    onOpenMessage: (String) -> Unit
) {
    val revision by Svc.store.revision.collectAsState()
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val safeBottom = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }

    val results: List<Message> = remember(revision, query, convId) {
        if (query.isBlank()) {
            emptyList()
        } else {
            runCatching { Svc.store.searchMessages(query.trim(), 200) }
                .getOrDefault(emptyList())
                .filter { it.convId == convId }
        }
    }

    // 进来就弹键盘，跟微信一样。
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(LiuliColors.Bg)
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏 + 搜索框
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(LiuliColors.Surface)
                    .padding(top = statusTop + 6.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(Radii.pill))
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
                    Row(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(Radii.field))
                            .background(LiuliColors.SurfaceSunken)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            LiuliIcons.Search,
                            contentDescription = null,
                            tint = LiuliColors.TextTertiary,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    "搜索聊天记录",
                                    color = LiuliColors.TextTertiary,
                                    fontSize = 14.5.sp
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 15.sp,
                                    color = LiuliColors.TextPrimary
                                ),
                                cursorBrush = SolidColor(LiuliColors.Accent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                            )
                        }
                        if (query.isNotEmpty()) {
                            Icon(
                                LiuliIcons.Close,
                                contentDescription = "清空",
                                tint = LiuliColors.TextTertiary,
                                modifier = Modifier
                                    .size(17.dp)
                                    .clickable { query = "" }
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

            when {
                query.isBlank() -> EmptyHint("输入关键词搜索本聊天记录", "支持文字、文件名")
                results.isEmpty() -> EmptyHint("没有找到相关聊天记录", "换个关键词试试")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = safeBottom + 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(results, key = { it.id }) { m ->
                        SearchRow(m, query) { onOpenMessage(m.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            LiuliIcons.Search,
            contentDescription = null,
            tint = LiuliColors.TextTertiary,
            modifier = Modifier.size(30.dp)
        )
        Text(
            title,
            color = LiuliColors.TextSecondary,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            subtitle,
            color = LiuliColors.TextTertiary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun SearchRow(message: Message, query: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(LiuliColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassAvatar(
            name = if (message.outgoing) "我" else message.senderName,
            seed = seedOf(message.senderId, message.senderName),
            size = 38.dp,
            glassSheen = false
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (message.outgoing) "我" else {
                        message.senderName.takeIf { it.isNotBlank() && it != "我" } ?: "对方"
                    },
                    color = LiuliColors.TextSecondary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.weight(1f))
                Text(
                    TimeFmt.chatStamp(message.sentAt),
                    color = LiuliColors.TextTertiary,
                    fontSize = 11.5.sp
                )
            }
            Text(
                highlight(message.preview, query),
                color = LiuliColors.TextPrimary,
                fontSize = 14.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

/** 把命中的关键词标成微信绿（大小写不敏感，全部命中都标）。 */
@Composable
private fun highlight(text: String, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return AnnotatedString(text)
    val lowerText = text.lowercase()
    val lowerQuery = q.lowercase()
    return buildAnnotatedString {
        var cursor = 0
        while (true) {
            val hit = lowerText.indexOf(lowerQuery, cursor)
            if (hit < 0) {
                append(text.substring(cursor))
                break
            }
            append(text.substring(cursor, hit))
            withStyle(SpanStyle(color = LiuliColors.Accent, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(hit, hit + q.length))
            }
            cursor = hit + q.length
        }
    }
}
