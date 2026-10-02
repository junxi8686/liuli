package com.liuli.btchat.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.ui.components.LiuliScaffold
import com.liuli.btchat.ui.components.RowPadding
import com.liuli.btchat.ui.components.RowSeparatorInset
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassEmptyState
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.SheetAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的收藏（二级页面）。
 *
 * A flat list of everything the user starred from a message's long-press menu:
 * each row shows where it came from, who said it, the text itself, when, and a
 * star. Tapping a row opens the conversation it belongs to; long-pressing
 * offers 取消收藏, which writes through
 * [com.liuli.btchat.core.ChatEngine.setStarred] so the message bubble loses its
 * star as well.
 */
@Composable
fun StarredScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val revision by Svc.store.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf<List<StarredRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sheetId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        rows = withContext(Dispatchers.IO) { readStarred() }
        loaded = true
    }

    val sheetRow = rows.firstOrNull { it.message.id == sheetId }

    LiuliScaffold(
        content = {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentPadding = PaddingValues(top = 50.dp, bottom = 40.dp)
            ) {
                if (rows.isEmpty() && loaded) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().background(LiuliColors.Surface)) {
                            GlassEmptyState(
                                icon = LiuliIcons.Star,
                                title = "还没有收藏",
                                message = "长按任意一条消息，选择「收藏」，它就会出现在这里"
                            )
                        }
                    }
                }

                items(rows, key = { it.message.id }) { row ->
                    StarredRowItem(
                        row = row,
                        onClick = { onOpenChat(row.message.convId) },
                        onLongClick = { sheetId = row.message.id }
                    )
                }
            }
        },
        overlay = {
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = "我的收藏",
                    subtitle = if (loaded) "${rows.size} 条" else "读取中…",
                    onBack = onBack
                )
                Spacer(Modifier.weight(1f))
            }

            GlassActionSheet(
                visible = sheetRow != null,
                onDismiss = { sheetId = null },
                title = sheetRow?.sourceTitle,
                actions = sheetRow?.let { row ->
                    listOf(
                        SheetAction(
                            icon = LiuliIcons.Chat,
                            label = "打开原会话",
                            description = row.sourceTitle
                        ) {
                            sheetId = null
                            onOpenChat(row.message.convId)
                        },
                        SheetAction(
                            icon = LiuliIcons.Star,
                            label = "取消收藏",
                            description = "消息仍保留在聊天里",
                            danger = true
                        ) {
                            sheetId = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    Svc.engine.setStarred(row.message.id, false)
                                }
                            }
                        }
                    )
                } ?: emptyList()
            )
        }
    )
}

/** One row of the collection: source, sender, text, time, star. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StarredRowItem(
    row: StarredRow,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(Modifier.fillMaxWidth().background(LiuliColors.Surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .heightIn(min = 68.dp)
                .padding(horizontal = RowPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassAvatar(
                name = row.avatarName,
                seed = row.avatarSeed,
                size = 44.dp,
                glassSheen = false
            )
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                // The **message** is the headline, the conversation is the
                // caption.
                //
                // The first version had it the other way round — the contact's
                // name and photo as the big top line with the message as small
                // grey text underneath — so a screen called 我的收藏 read as a
                // list of people, and the user's conclusion was that favouriting
                // a message had favourited the whole contact. Data-store
                // confirmed the stored data was right all along; only the
                // hierarchy was wrong.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (row.message.recalled) "[已撤回]" else row.message.preview,
                        color = LiuliColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        TimeFmt.listStamp(row.message.sentAt),
                        color = LiuliColors.TextTertiary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Text(
                    "来自 ${row.sourceTitle}",
                    color = LiuliColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                LiuliIcons.Star,
                contentDescription = "已收藏",
                tint = LiuliColors.Amber,
                modifier = Modifier.size(18.dp)
            )
        }
        GlassDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            inset = RowSeparatorInset - 4.dp
        )
    }
}

/** One starred message with the context it came from. */
private data class StarredRow(
    val message: Message,
    val sourceTitle: String,
    val avatarName: String,
    val avatarSeed: Int
)

private fun readStarred(): List<StarredRow> {
    val store = Svc.store
    return store.starredMessages().map { message ->
        val conv = store.conversation(message.convId)
        val peerTitle = conv?.peerId?.let { store.contact(it)?.display }?.takeIf { it.isNotBlank() }
        val title = peerTitle
            ?: conv?.title?.takeIf { it.isNotBlank() }
            ?: "未知会话"

        // The avatar has to belong to whoever sent *this* message, otherwise a
        // message you sent appears under the other person's face. The sender's
        // own name is what a group chat needs; a direct chat has no per-message
        // identity to show, so it falls back to the conversation.
        val outgoing = message.outgoing
        val myPrefs = Svc.settings.current()
        val avatarName = when {
            outgoing -> myPrefs.myName.ifBlank { "我" }
            message.senderName.isNotBlank() -> message.senderName
            else -> title
        }
        val avatarSeed = when {
            outgoing -> myPrefs.myAvatarSeed
            conv?.kind == ConvKind.DIRECT ->
                store.contact(conv.peerId.orEmpty())?.avatarSeed ?: conv.avatarSeed
            else -> message.senderName.hashCode()
        }
        StarredRow(
            message = message,
            sourceTitle = title,
            avatarName = avatarName,
            avatarSeed = avatarSeed
        )
    }
}
