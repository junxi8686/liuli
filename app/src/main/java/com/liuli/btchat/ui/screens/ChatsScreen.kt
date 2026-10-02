package com.liuli.btchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.Peer
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.seedOf
import com.liuli.btchat.ui.components.ConversationRow
import com.liuli.btchat.ui.components.LiuliScaffold
import com.liuli.btchat.ui.components.ProfileSheet
import com.liuli.btchat.ui.components.RowSeparatorInset
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.GlassBadge
import com.liuli.btchat.ui.glass.GlassBottomNav
import com.liuli.btchat.ui.glass.GlassButton
import com.liuli.btchat.ui.glass.GlassConfirmDialog
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassEmptyState
import com.liuli.btchat.ui.glass.GlassFab
import com.liuli.btchat.ui.glass.GlassIconButton
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassLinkPill
import com.liuli.btchat.ui.glass.GlassPrimaryButton
import com.liuli.btchat.ui.glass.GlassSearchField
import com.liuli.btchat.ui.glass.GlassSheet
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.NavItem
import com.liuli.btchat.ui.glass.SheetAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话列表（首页）。
 *
 * A WeChat-style chat list: one continuous run of white 72dp rows separated by
 * hairlines, a grey search strip above it, and only three pieces of chrome —
 * the frosted top bar, the solid accent FAB and the white tab bar. No list row
 * draws glass, which is what keeps a fast flick at frame rate.
 *
 * Data is read from [com.liuli.btchat.core.ChatStore] on `Dispatchers.IO` and
 * re-read whenever the store bumps its `revision`; titles are resolved live
 * from the contact book so a renamed peer no longer shows the name frozen into
 * the conversation when it was created.
 */
@Composable
fun ChatsScreen(
    onOpenChat: (String) -> Unit,
    onOpenContacts: () -> Unit,
    onNewGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMe: (() -> Unit)? = null,
    /** 平板双栏：右侧当前打开的会话，列表里给它一层高亮（手机传 null）。 */
    selectedConvId: String? = null
) {
    val prefs by Svc.settings.flow.collectAsStateWithLifecycle()
    val link by Svc.engine.link.collectAsStateWithLifecycle()
    val revision by Svc.store.revision.collectAsStateWithLifecycle()
    val typing by Svc.engine.typing.collectAsStateWithLifecycle()
    val nearby by Svc.engine.peers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<ChatRowUi>>(emptyList()) }
    var contactCount by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    var fabOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var sheetId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }

    LaunchedEffect(revision, query) {
        val snapshot = withContext(Dispatchers.IO) { readSnapshot(query) }
        rows = snapshot.rows
        contactCount = snapshot.contactCount
        loaded = true
    }

    LaunchedEffect(revision, addOpen) {
        if (addOpen) contacts = withContext(Dispatchers.IO) { Svc.store.contacts() }
    }

    val unread = rows.sumOf { it.conv.unread }
    val sheetRow = rows.firstOrNull { it.conv.id == sheetId }
    val deleteConv = rows.firstOrNull { it.conv.id == deleteId }?.conv

    LiuliScaffold(
        content = {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentPadding = PaddingValues(top = 50.dp, bottom = 96.dp)
            ) {
                item(key = "search") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(LiuliColors.Bg)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        GlassSearchField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "搜索",
                            leading = {
                                Icon(
                                    LiuliIcons.Search,
                                    contentDescription = null,
                                    tint = LiuliColors.TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            trailing = {
                                if (query.isNotEmpty()) {
                                    GlassIconButton(
                                        onClick = { query = "" },
                                        size = 26.dp,
                                        level = GlassLevel.Thin,
                                        bordered = false
                                    ) {
                                        Icon(
                                            LiuliIcons.Close,
                                            contentDescription = "清空搜索",
                                            tint = LiuliColors.TextTertiary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }
                }

                if (rows.isEmpty() && loaded) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().background(LiuliColors.Surface)) {
                            GlassEmptyState(
                                icon = if (query.isBlank()) LiuliIcons.Chats else LiuliIcons.Search,
                                title = if (query.isBlank()) "还没有会话" else "没有找到会话",
                                message = if (query.isBlank()) {
                                    "点右下角的加号发起群聊，或到通讯录里找一位朋友"
                                } else {
                                    "换个关键词试试，会同时匹配会话名和消息内容"
                                },
                                action = {
                                    if (query.isBlank()) {
                                        GlassPrimaryButton(onClick = onNewGroup) {
                                            Text(
                                                "发起群聊",
                                                color = LiuliColors.TextOnAccent,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    } else {
                                        GlassButton(onClick = { query = "" }) {
                                            Text(
                                                "清空搜索",
                                                color = LiuliColors.TextPrimary,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                items(rows, key = { it.conv.id }) { row ->
                    // 平板双栏：当前在右侧打开的会话加一层底色，微信的平板上就是这个样子。
                    // 高亮画在行的外面（不动 ui-home 的 ConversationRow）。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (row.conv.id == selectedConvId) {
                                    LiuliColors.SurfaceSunken
                                } else {
                                    Color.Transparent
                                }
                            )
                    ) {
                        ConversationRow(
                            conv = row.conv,
                            title = row.title,
                            preview = row.preview,
                            sender = row.sender,
                            groupSeeds = row.seeds,
                            online = row.online,
                            typing = row.conv.id in typing,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Svc.engine.markRead(row.conv.id) }
                                }
                                onOpenChat(row.conv.id)
                            },
                            onLongClick = { sheetId = row.conv.id },
                            onToggleMute = {
                                val target = row.conv
                                scope.mutate {
                                    Svc.store.saveConversation(target.copy(muted = !target.muted))
                                }
                            },
                            onDelete = { deleteId = row.conv.id }
                        )
                    }
                }
            }
        },
        overlay = {
            // No insets here: GlassTopBar/GlassBottomNav carry their own, so the
            // bars' white reaches behind the system bars instead of leaving a
            // band of page colour above them.
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = "琉璃",
                    subtitle = link.message.ifBlank { "本机直连，不需要网络" },
                    trailing = {
                        GlassLinkPill(link = link, onClick = onOpenContacts)
                    }
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box {
                        GlassFab(onClick = { fabOpen = true })
                        if (unread > 0) {
                            GlassBadge(
                                unread,
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(start = 14.dp)
                            )
                        }
                    }
                }
                GlassBottomNav(
                    items = ListNav,
                    selected = 0,
                    onSelect = { index ->
                        when (index) {
                            1 -> onOpenContacts()
                            2 -> onOpenMe?.invoke()
                            3 -> onOpenSettings()
                        }
                    },
                    badgeFor = { if (it == 0) unread else 0 }
                )
            }

            GlassActionSheet(
                visible = fabOpen,
                onDismiss = { fabOpen = false },
                title = "开始新的对话",
                actions = listOf(
                    SheetAction(LiuliIcons.Group, "发起群聊", "邀请联系人一起聊") {
                        fabOpen = false
                        onNewGroup()
                    },
                    SheetAction(LiuliIcons.Add, "加联系人", "输入设备 ID 或蓝牙地址") {
                        fabOpen = false
                        addOpen = true
                    },
                    SheetAction(LiuliIcons.Discover, "打开发现", "搜索并连接附近设备") {
                        fabOpen = false
                        onOpenContacts()
                    },
                    SheetAction(LiuliIcons.Contacts, "我的资料", "昵称、状态与头像颜色") {
                        fabOpen = false
                        profileOpen = true
                    }
                )
            )

            GlassActionSheet(
                visible = sheetRow != null,
                onDismiss = { sheetId = null },
                title = sheetRow?.title,
                actions = sheetRow?.conv?.let { conv ->
                    listOf(
                        SheetAction(
                            icon = LiuliIcons.Pin,
                            label = if (conv.pinned) "取消置顶" else "置顶会话",
                            description = "置顶后始终排在最前"
                        ) {
                            sheetId = null
                            scope.mutate { Svc.store.saveConversation(conv.copy(pinned = !conv.pinned)) }
                        },
                        SheetAction(
                            icon = LiuliIcons.Mute,
                            label = if (conv.muted) "取消免打扰" else "消息免打扰",
                            description = "新消息不再计数"
                        ) {
                            sheetId = null
                            scope.mutate { Svc.store.saveConversation(conv.copy(muted = !conv.muted)) }
                        },
                        SheetAction(
                            icon = LiuliIcons.Checked,
                            label = "标为已读",
                            description = if (conv.unread > 0) "${conv.unread} 条未读" else "已经读完了"
                        ) {
                            sheetId = null
                            scope.mutate {
                                Svc.engine.markRead(conv.id)
                                Svc.store.markConversationRead(conv.id)
                            }
                        },
                        SheetAction(
                            icon = LiuliIcons.Delete,
                            label = "删除会话",
                            description = "只删除本机记录",
                            danger = true
                        ) {
                            deleteId = conv.id
                            sheetId = null
                        }
                    )
                } ?: emptyList()
            )

            GlassConfirmDialog(
                visible = deleteConv != null,
                title = "删除会话",
                message = "「${deleteConv?.title.orEmpty()}」和它的聊天记录会从本机移除，对方不受影响。",
                confirmText = "删除",
                destructive = true,
                onDismiss = { deleteId = null },
                onConfirm = {
                    val target = deleteConv
                    deleteId = null
                    if (target != null) {
                        scope.mutate { Svc.store.deleteConversation(target.id) }
                    }
                }
            )

            AddContactSheet(
                visible = addOpen,
                onDismiss = { addOpen = false },
                contacts = contacts,
                peers = nearby,
                onAdd = { name, raw ->
                    addOpen = false
                    if (raw.isNotBlank()) {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { saveManualContact(name, raw) }
                            if (saved != null) contacts = contacts + saved
                        }
                    }
                }
            )

            ProfileSheet(
                visible = profileOpen,
                prefs = prefs,
                adapterName = link.adapterName,
                conversationCount = rows.size,
                contactCount = contactCount,
                onDismiss = { profileOpen = false },
                onApply = { name, status, seed ->
                    Svc.settings.edit {
                        it.copy(myName = name, myStatus = status, myAvatarSeed = seed)
                    }
                }
            )
        }
    )
}

/** Everything a row needs, resolved off the main thread in one pass. */
private data class ChatRowUi(
    val conv: Conversation,
    val title: String,
    val preview: String,
    val sender: String?,
    val seeds: List<Int>,
    val online: Boolean
)

private data class ChatSnapshot(
    val rows: List<ChatRowUi>,
    val contactCount: Int
)

private val ListNav = tabItems

/** Runs one store mutation off the main thread; the store bumps `revision` for us. */
private fun CoroutineScope.mutate(block: () -> Unit) {
    launch { withContext(Dispatchers.IO) { block() } }
}

private val MacPattern = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

/**
 * Reads conversations, previews, group seeds and presence. Called on
 * `Dispatchers.IO` only.
 *
 * The title comes from the contact book first: [Conversation.title] is frozen
 * when the conversation is created, so a contact renamed later — or a peer
 * whose name only became known after the handshake — would otherwise keep
 * showing the stale string.
 */
private fun readSnapshot(query: String): ChatSnapshot {
    val store = Svc.store
    val all = store.conversations()
    val messageHits: Set<String> =
        if (query.isBlank()) emptySet()
        else store.searchMessages(query).map { it.convId }.toSet()

    val rows = all.asSequence()
        .map { conv ->
            val last = store.latestMessage(conv.id)
            val live = conv.peerId
                ?.let { store.contact(it)?.display }
                ?.takeIf { it.isNotBlank() }
            ChatRowUi(
                conv = conv,
                title = live ?: conv.title,
                preview = when {
                    conv.draft.isNotBlank() -> conv.draft
                    last != null -> last.preview
                    else -> conv.lastPreview
                },
                sender = last?.takeIf { conv.kind == ConvKind.GROUP && !it.outgoing }?.senderName,
                seeds = if (conv.kind == ConvKind.GROUP) {
                    store.members(conv.id).map { it.avatarSeed }
                } else {
                    emptyList()
                },
                online = conv.peerId?.let { Svc.engine.isConnected(it) } == true
            )
        }
        .filter { row ->
            query.isBlank() ||
                row.title.contains(query, ignoreCase = true) ||
                row.preview.contains(query, ignoreCase = true) ||
                row.conv.id in messageHits
        }
        .sortedWith(
            compareByDescending<ChatRowUi> { it.conv.pinned }
                .thenByDescending { it.conv.lastMessageAt }
        )
        .toList()

    return ChatSnapshot(rows, store.contacts().size)
}

/** Persists one manually entered contact. Called on `Dispatchers.IO` only. */
private fun saveManualContact(name: String, raw: String): Contact? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val isMac = MacPattern.matches(trimmed)
    val address = if (isMac) trimmed.uppercase() else ""
    val contact = Contact(
        deviceId = if (isMac) address else trimmed,
        name = name.trim().ifBlank { if (isMac) address else trimmed.take(8) },
        address = address,
        avatarSeed = seedOf(trimmed.lowercase()),
        lastSeen = System.currentTimeMillis()
    )
    Svc.store.saveContact(contact)
    if (isMac) Svc.engine.refreshBonded()
    return contact
}

/** 「加联系人」 sheet: manual entry plus one-tap adding of devices already in range. */
@Composable
private fun AddContactSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    contacts: List<Contact>,
    peers: List<Peer>,
    onAdd: (String, String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var raw by rememberSaveable { mutableStateOf("") }

    val known = remember(contacts) {
        contacts.flatMap { listOf(it.deviceId, it.address) }.filter { it.isNotBlank() }.toSet()
    }
    val suggestion = remember(peers, known) {
        peers.filter { it.address !in known && it.deviceId !in known }.take(4)
    }

    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 470.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Text(
                "加联系人",
                color = LiuliColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "输入对方的设备 ID 或蓝牙地址（形如 AA:BB:CC:DD:EE:FF），保存后就能直接发起单聊。",
                color = LiuliColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )

            GlassSearchField(
                value = name,
                onValueChange = { name = it.take(24) },
                placeholder = "对方昵称（可留空）",
                leading = {
                    Icon(
                        LiuliIcons.Contacts,
                        contentDescription = null,
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            )
            GlassSearchField(
                value = raw,
                onValueChange = { raw = it.take(64) },
                placeholder = "设备 ID 或蓝牙地址",
                leading = {
                    Icon(
                        LiuliIcons.Link,
                        contentDescription = null,
                        tint = LiuliColors.TextTertiary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            )

            if (suggestion.isNotEmpty()) {
                Text(
                    "在附近发现",
                    color = LiuliColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
                suggestion.forEach { peer ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                peer.name.ifBlank { "未知设备" },
                                color = LiuliColors.TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                peer.address.ifBlank { peer.deviceId },
                                color = LiuliColors.TextTertiary,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        GlassButton(
                            onClick = { onAdd(peer.name, peer.address.ifBlank { peer.deviceId }) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "添加",
                                color = LiuliColors.Accent,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    GlassDivider(inset = RowSeparatorInset)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlassButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "取消",
                            color = LiuliColors.TextPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                GlassPrimaryButton(
                    onClick = { onAdd(name, raw) },
                    modifier = Modifier.weight(1f),
                    enabled = raw.isNotBlank()
                ) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            if (raw.isBlank()) "填写设备地址" else "保存联系人",
                            color = LiuliColors.TextOnAccent,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
