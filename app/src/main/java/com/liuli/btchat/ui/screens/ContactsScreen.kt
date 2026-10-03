package com.liuli.btchat.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.Svc
import com.liuli.btchat.ui.components.ContactRow
import com.liuli.btchat.ui.components.LiuliScaffold
import com.liuli.btchat.ui.components.ProfileSheet
import com.liuli.btchat.ui.components.RowAvatarSize
import com.liuli.btchat.ui.components.RowPadding
import com.liuli.btchat.ui.components.RowSeparatorInset
import com.liuli.btchat.ui.components.contactSubtitle
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassBadge
import com.liuli.btchat.ui.glass.GlassBottomNav
import com.liuli.btchat.ui.glass.GlassButton
import com.liuli.btchat.ui.glass.GlassConfirmDialog
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassEmptyState
import com.liuli.btchat.ui.glass.GlassIconButton
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassPrimaryButton
import com.liuli.btchat.ui.glass.GlassSearchField
import com.liuli.btchat.ui.glass.GlassSheet
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.GroupAvatar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.NavItem
import com.liuli.btchat.ui.glass.SheetAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通讯录：群聊 + 联系人。**底部栏的 Tab 根页之一**，所以 `onBack` 默认是
 * null —— Tab 根页不显示返回箭头，切换靠底部栏和系统返回手势。
 *
 * WeChat's address book: a white "me" row, then a grey search strip, then two
 * sections of continuous white rows with inset hairlines. Only the top bar and
 * the modal sheets carry any material — the rows themselves are flat, so a long
 * contact list scrolls without touching the GPU compositor.
 *
 * Tapping a contact resolves (creating it if needed) the direct conversation on
 * `Dispatchers.IO` and only then opens it, so a contact who was never messaged
 * still gets a real conversation instead of a dead end.
 */
@Composable
fun ContactsScreen(
    onOpenChat: (String) -> Unit,
    onOpenDiscover: () -> Unit,
    onNewGroup: () -> Unit,
    onBack: (() -> Unit)? = null,
    onOpenChats: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenContact: ((String) -> Unit)? = null,
    onOpenMe: (() -> Unit)? = null
) {
    val prefs by Svc.settings.flow.collectAsStateWithLifecycle()
    val link by Svc.engine.link.collectAsStateWithLifecycle()
    val revision by Svc.store.revision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var groups by remember { mutableStateOf<List<GroupUi>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<ContactUi>>(emptyList()) }
    var unread by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    var profileOpen by remember { mutableStateOf(false) }
    var contactSheet by remember { mutableStateOf<Contact?>(null) }
    var groupSheet by remember { mutableStateOf<GroupUi?>(null) }
    var renameTarget by remember { mutableStateOf<GroupUi?>(null) }
    var remarkTarget by remember { mutableStateOf<Contact?>(null) }
    var deleteContact by remember { mutableStateOf<Contact?>(null) }
    var leaveTarget by remember { mutableStateOf<GroupUi?>(null) }
    var dissolveTarget by remember { mutableStateOf<GroupUi?>(null) }
    var localProfile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        val snapshot = withContext(Dispatchers.IO) { readContacts() }
        groups = snapshot.groups
        contacts = snapshot.contacts
        unread = snapshot.unread
        loaded = true
    }

    val visibleContacts = remember(contacts, query) {
        if (query.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.contact.display.contains(query, ignoreCase = true) ||
                    it.contact.name.contains(query, ignoreCase = true) ||
                    it.contact.address.contains(query, ignoreCase = true)
            }
        }
    }

    // 「查看资料」: when the shell wires onOpenContact the profile is pushed as a
    // real second-level page; without that callback we still open it here, so
    // the entry can never become a dead tap.
    val inlineProfile = localProfile
    if (inlineProfile != null && onOpenContact == null) {
        BackHandler { localProfile = null }
        ContactProfileScreen(
            deviceId = inlineProfile,
            onBack = { localProfile = null },
            onOpenChat = onOpenChat
        )
        return
    }

    LiuliScaffold(
        content = {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentPadding = PaddingValues(top = 50.dp, bottom = 96.dp)
            ) {
                item(key = "me") {
                    MeRow(
                        name = prefs.myName.ifBlank { "我" },
                        status = prefs.myStatus,
                        seed = prefs.myAvatarSeed,
                        deviceId = prefs.myDeviceId,
                        onClick = { profileOpen = true }
                    )
                }

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
                            placeholder = "搜索联系人",
                            leading = {
                                Icon(
                                    LiuliIcons.Search,
                                    contentDescription = null,
                                    tint = LiuliColors.TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                if (groups.isNotEmpty()) {
                    item(key = "groupHeader") { SectionHeader("群聊（${groups.size}）") }
                    itemsIndexed(groups, key = { index, item -> "g-${item.id}-$index" }) { _, group ->
                        val badge: (@Composable () -> Unit)? =
                            if (group.unread > 0) ({ GlassBadge(group.unread) }) else null
                        ContactRow(
                            name = group.title,
                            seed = group.seeds.firstOrNull() ?: 0,
                            subtitle = "${group.memberCount} 位成员",
                            avatar = { GroupAvatar(seeds = group.seeds, size = RowAvatarSize) },
                            trailing = badge,
                            onClick = { onOpenChat(group.id) },
                            onLongClick = { groupSheet = group }
                        )
                    }
                }

                item(key = "contactHeader") {
                    SectionHeader("联系人（${visibleContacts.size}）")
                }

                if (visibleContacts.isEmpty() && loaded) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().background(LiuliColors.Surface)) {
                            GlassEmptyState(
                                icon = LiuliIcons.Contacts,
                                title = if (query.isBlank()) "还没有联系人" else "没有匹配的联系人",
                                message = if (query.isBlank()) {
                                    "到发现页搜索附近的设备，配对成功后会出现在这里"
                                } else {
                                    "换一个名字、设备 ID 或蓝牙地址试试"
                                },
                                action = {
                                    if (query.isBlank()) {
                                        GlassPrimaryButton(onClick = onOpenDiscover) {
                                            Text(
                                                "去发现设备",
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

                itemsIndexed(
                    visibleContacts,
                    key = { index, item -> "c-${item.contact.deviceId}-$index" }
                ) { _, item ->
                    ContactRow(
                        name = item.contact.display,
                        seed = item.contact.avatarSeed,
                        remark = item.contact.name.takeIf { item.contact.remark.isNotBlank() },
                        subtitle = item.subtitle,
                        online = item.online,
                        onClick = {
                        // WeChat behaviour: a contact in the address book opens
                        // their profile, not a chat. "发消息" lives on the
                        // profile.
                        val open = onOpenContact
                        if (open != null) open(item.contact.deviceId) else localProfile = item.contact.deviceId
                    },
                        onLongClick = { contactSheet = item.contact }
                    )
                }
            }
        },
        overlay = {
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = "通讯录",
                    subtitle = "${groups.size} 个群 · ${contacts.size} 位联系人",
                    onBack = onBack,
                    trailing = {
                        GlassIconButton(
                            onClick = onOpenDiscover,
                            size = 38.dp,
                            bordered = false
                        ) {
                            Icon(
                                LiuliIcons.Discover,
                                contentDescription = "发现设备",
                                tint = LiuliColors.Accent,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        GlassIconButton(
                            onClick = onNewGroup,
                            size = 38.dp,
                            bordered = false
                        ) {
                            Icon(
                                LiuliIcons.Group,
                                contentDescription = "发起群聊",
                                tint = LiuliColors.TextPrimary,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                )
                Spacer(Modifier.weight(1f))
                GlassBottomNav(
                    items = TabItems,
                    selected = 1,
                    onSelect = { index ->
                        when (index) {
                            0 -> onOpenChats?.invoke()
                            2 -> onOpenMe?.invoke()
                            3 -> onOpenSettings?.invoke()
                        }
                    },
                    badgeFor = { if (it == 0) unread else 0 }
                )
            }

            GlassActionSheet(
                visible = contactSheet != null,
                onDismiss = { contactSheet = null },
                title = contactSheet?.display,
                actions = contactSheet?.let { target ->
                    listOf(
                        SheetAction(LiuliIcons.Contacts, "查看资料", "备注、免打扰、置顶与拉黑标记") {
                            contactSheet = null
                            if (onOpenContact != null) onOpenContact(target.deviceId) else localProfile = target.deviceId
                        },
                        SheetAction(LiuliIcons.Chat, "发消息", "打开与 TA 的单聊") {
                            contactSheet = null
                            openDirect(target, scope, onOpenChat)
                        },
                        SheetAction(LiuliIcons.Edit, "改备注", "只在本机生效") {
                            remarkTarget = target
                            contactSheet = null
                        },
                        SheetAction(
                            icon = LiuliIcons.Delete,
                            label = "删除联系人",
                            description = "聊天记录会保留",
                            danger = true
                        ) {
                            deleteContact = target
                            contactSheet = null
                        }
                    )
                } ?: emptyList()
            )

            GlassActionSheet(
                visible = groupSheet != null,
                onDismiss = { groupSheet = null },
                title = groupSheet?.title,
                actions = groupSheet?.let { target ->
                    listOf(
                        SheetAction(LiuliIcons.Chat, "打开群聊", "${target.memberCount} 位成员") {
                            groupSheet = null
                            onOpenChat(target.id)
                        },
                        SheetAction(LiuliIcons.Edit, "重命名群聊", "新名字会同步给成员") {
                            renameTarget = target
                            groupSheet = null
                        },
                        SheetAction(LiuliIcons.Logout, "退出群聊", "保留本机聊天记录") {
                            leaveTarget = target
                            groupSheet = null
                        },
                        SheetAction(
                            icon = LiuliIcons.Delete,
                            label = "解散群聊",
                            description = "仅群主可用，成员会收到通知",
                            danger = true
                        ) {
                            dissolveTarget = target
                            groupSheet = null
                        }
                    )
                } ?: emptyList()
            )

            TextInputSheet(
                visible = renameTarget != null,
                title = "重命名群聊",
                initial = renameTarget?.title.orEmpty(),
                placeholder = "群名称",
                onDismiss = { renameTarget = null },
                onConfirm = { name ->
                    val target = renameTarget
                    renameTarget = null
                    if (target != null && name.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Svc.engine.renameGroup(target.id, name) }
                        }
                    }
                }
            )

            TextInputSheet(
                visible = remarkTarget != null,
                title = "改备注",
                initial = remarkTarget?.remark.orEmpty(),
                placeholder = remarkTarget?.name.orEmpty().ifBlank { "备注名" },
                onDismiss = { remarkTarget = null },
                onConfirm = { remark ->
                    val target = remarkTarget
                    remarkTarget = null
                    if (target != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Svc.store.saveContact(target.copy(remark = remark))
                            }
                        }
                    }
                }
            )

            GlassConfirmDialog(
                visible = deleteContact != null,
                title = "删除联系人",
                message = "「${deleteContact?.display.orEmpty()}」会从通讯录移除，已有的聊天记录仍然保留。",
                confirmText = "删除",
                destructive = true,
                onDismiss = { deleteContact = null },
                onConfirm = {
                    val target = deleteContact
                    deleteContact = null
                    if (target != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Svc.store.deleteContact(target.deviceId) }
                        }
                    }
                }
            )

            GlassConfirmDialog(
                visible = leaveTarget != null,
                title = "退出群聊",
                message = "退出后不再接收「${leaveTarget?.title.orEmpty()}」的新消息，本机记录保留。",
                confirmText = "退出",
                destructive = true,
                onDismiss = { leaveTarget = null },
                onConfirm = {
                    val target = leaveTarget
                    leaveTarget = null
                    if (target != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Svc.engine.leaveGroup(target.id) }
                        }
                    }
                }
            )

            GlassConfirmDialog(
                visible = dissolveTarget != null,
                title = "解散群聊",
                message = "「${dissolveTarget?.title.orEmpty()}」会对所有成员解散，无法撤销。",
                confirmText = "解散",
                destructive = true,
                onDismiss = { dissolveTarget = null },
                onConfirm = {
                    val target = dissolveTarget
                    dissolveTarget = null
                    if (target != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Svc.engine.dissolveGroup(target.id) }
                        }
                    }
                }
            )

            ProfileSheet(
                visible = profileOpen,
                prefs = prefs,
                adapterName = link.adapterName,
                conversationCount = groups.size,
                contactCount = contacts.size,
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

/** One group in the 群聊 section. */
private data class GroupUi(
    val id: String,
    val title: String,
    val seeds: List<Int>,
    val memberCount: Int,
    val unread: Int,
    val lastMessageAt: Long
)

/** One row of the 联系人 section, with presence already resolved. */
private data class ContactUi(
    val contact: Contact,
    val online: Boolean,
    val subtitle: String
)

/** Everything the screen reads in one IO pass. */
private data class ContactsSnapshot(
    val groups: List<GroupUi>,
    val contacts: List<ContactUi>,
    val unread: Int
)

/** The three tab-bar entries, shared by every tab root. */
private val TabItems = tabItems

/** Groups and contacts, read in a single IO pass. */
private fun readContacts(): ContactsSnapshot {
    val store = Svc.store
    val groups = store.groupIds()
        .mapNotNull { id ->
            val conv = store.conversation(id) ?: return@mapNotNull null
            val members = store.members(id)
            GroupUi(
                id = id,
                title = conv.title,
                seeds = members.map { it.avatarSeed },
                memberCount = members.size.coerceAtLeast(conv.memberCount),
                unread = conv.unread,
                lastMessageAt = conv.lastMessageAt
            )
        }
        .sortedByDescending { it.lastMessageAt }

    val peers = Svc.engine.peers.value
    val contacts = store.contacts()
        .sortedBy { it.display.lowercase() }
        .map { contact ->
            val online = Svc.engine.isConnected(contact.deviceId) ||
                peers.any { it.connected && (it.deviceId == contact.deviceId || it.address == contact.address) }
            ContactUi(
                contact = contact,
                online = online,
                subtitle = if (online) "在线 · 可以聊天" else contactSubtitle(contact)
            )
        }
    return ContactsSnapshot(groups, contacts, store.totalUnread())
}

/** Resolves (creating it if needed) the direct conversation, then opens it. */
private fun openDirect(
    contact: Contact,
    scope: CoroutineScope,
    onOpenChat: (String) -> Unit
) {
    scope.launch {
        val conv = withContext(Dispatchers.IO) {
            runCatching { Svc.engine.directConversationWith(contact) }.getOrNull()
        }
        if (conv != null) onOpenChat(conv.id)
    }
}

/** Grey strip that separates two white sections, WeChat style. */
@Composable
private fun SectionHeader(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(LiuliColors.Bg)
            .padding(start = 16.dp, top = 10.dp, bottom = 6.dp)
    ) {
        Text(
            text,
            color = LiuliColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** The white row at the top of 通讯录 that opens the profile editor. */
@Composable
private fun MeRow(
    name: String,
    status: String,
    seed: Int,
    deviceId: String,
    onClick: () -> Unit
) {
    Box(Modifier.fillMaxWidth().background(LiuliColors.Surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 76.dp)
                .padding(horizontal = RowPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassAvatar(name = name, seed = seed, size = 56.dp, glassSheen = false)
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = LiuliColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    status.ifBlank { "在蓝牙上" },
                    color = LiuliColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (deviceId.isNotBlank()) {
                    Text(
                        "琉璃号 ${deviceId.take(12)}",
                        color = LiuliColors.TextTertiary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Icon(
                LiuliIcons.ChevronRight,
                contentDescription = null,
                tint = LiuliColors.TextTertiary,
                modifier = Modifier.size(18.dp)
            )
        }
        GlassDivider(
            modifier = Modifier.align(Alignment.BottomStart),
            inset = RowSeparatorInset
        )
    }
}

/** Small editor shared by 群名称 and 联系人备注. */
@Composable
private fun TextInputSheet(
    visible: Boolean,
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    LaunchedEffect(visible, initial) {
        if (visible) value = initial
    }

    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text(
            title,
            color = LiuliColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(10.dp))
        GlassSearchField(
            value = value,
            onValueChange = { value = it.take(24) },
            placeholder = placeholder
        )
        Spacer(Modifier.height(12.dp))
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
                onClick = { onConfirm(value.trim()) },
                modifier = Modifier.weight(1f)
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "保存",
                        color = LiuliColors.TextOnAccent,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
