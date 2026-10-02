package com.liuli.btchat.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.Svc
import com.liuli.btchat.ui.components.LiuliScaffold
import com.liuli.btchat.ui.components.lastSeenText
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassButton
import com.liuli.btchat.ui.glass.GlassConfirmDialog
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassIconButton
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassPrimaryButton
import com.liuli.btchat.ui.glass.GlassSearchField
import com.liuli.btchat.ui.glass.GlassSheet
import com.liuli.btchat.ui.glass.GlassSwitch
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii
import com.liuli.btchat.ui.glass.SheetAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对方主页（二级页面）。
 *
 * Everything on this page writes to something real:
 *  * 备注 — `store.saveContact`, and the matching DIRECT conversation title is
 *    refreshed in the same pass so the list shows the new name immediately.
 *  * 免打扰 / 置顶 — the DIRECT conversation's `muted` / `pinned`, which the
 *    会话 list already renders (grey dot, pin icon, sort order).
 *  * 清空聊天记录 — attachments deleted through `MediaVault` first, then
 *    `store.clearHistory`.
 *  * 删除好友 — `store.deleteContact`.
 *  * 拉黑 is a **local marker only**; the row and the toast both say so. It
 *    never claims to block the peer — it only tags the contact in this phone's
 *    own lists.
 */
@Composable
fun ContactProfileScreen(
    deviceId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val context = LocalContext.current
    val revision by Svc.store.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<ContactProfileData?>(null) }
    var remarkOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(isContactBlocked(context, deviceId)) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision, deviceId) {
        profile = withContext(Dispatchers.IO) { readProfile(deviceId) }
    }

    val data = profile
    val contact = data?.contact
    val conv = data?.conv
    val display = contact?.display ?: conv?.title ?: deviceId.take(8)

    fun openChat() {
        val target = contact
        if (target != null) {
            scope.launch {
                val opened = withContext(Dispatchers.IO) {
                    runCatching { Svc.engine.directConversationWith(target) }.getOrNull()
                }
                if (opened != null) onOpenChat(opened.id) else notice = "无法打开会话：设备信息不完整"
            }
        } else if (conv != null) {
            onOpenChat(conv.id)
        } else {
            notice = "无法打开会话：设备信息不完整"
        }
    }

    /**
     * 发起语音/视频通话。
     *
     * 通话要挂在会话上，所以资料页里如果还没有会话就先建一个（和「发消息」同一条路），
     * 然后再交给 [CallBridge]。失败时把 Engine 给的原因原样提示，不静默。
     */
    fun startCall(video: Boolean) {
        val existing = conv
        if (existing != null) {
            CallBridge.startCall(existing.id, video)?.let { notice = it }
            return
        }
        val target = contact
        if (target == null) {
            notice = "无法发起通话：设备信息不完整"
            return
        }
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching { Svc.engine.directConversationWith(target) }.getOrNull()
            }
            if (opened == null) {
                notice = "无法发起通话：设备信息不完整"
            } else {
                CallBridge.startCall(opened.id, video)?.let { notice = it }
            }
        }
    }

    LiuliScaffold(
        content = {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentPadding = PaddingValues(top = 50.dp, bottom = 120.dp)
            ) {
                item(key = "card") {
                    Column(
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .background(LiuliColors.Surface, RoundedCornerShape(Radii.card))
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GlassAvatar(
                            name = display,
                            seed = contact?.avatarSeed ?: conv?.avatarSeed ?: 0,
                            size = 76.dp,
                            online = data?.online == true,
                            glassSheen = false
                        )
                        Text(
                            display,
                            color = LiuliColors.TextPrimary,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when {
                                data?.online == true -> "在线 · 可以聊天"
                                !contact?.address.isNullOrBlank() -> "蓝牙地址 ${contact?.address}"
                                (contact?.lastSeen ?: 0L) > 0L -> lastSeenText(contact?.lastSeen ?: 0L)
                                else -> "还没有配对记录"
                            },
                            color = if (data?.online == true) LiuliColors.Success else LiuliColors.TextTertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (blocked) {
                            Text(
                                "已加入黑名单（本地标记）",
                                color = LiuliColors.Danger,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassPrimaryButton(
                                onClick = { openChat() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "发消息",
                                        color = LiuliColors.TextOnAccent,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                            // 微信的资料页在「发消息」旁边就有语音/视频两个圆钮
                            GlassIconButton(
                                onClick = { startCall(video = false) },
                                size = 46.dp
                            ) {
                                Icon(
                                    LiuliIcons.Mic,
                                    contentDescription = "语音通话",
                                    tint = LiuliColors.TextPrimary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                            GlassIconButton(
                                onClick = { startCall(video = true) },
                                size = 46.dp
                            ) {
                                Icon(
                                    LiuliIcons.Video,
                                    contentDescription = "视频通话",
                                    tint = LiuliColors.TextPrimary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }
                }

                item(key = "basic") {
                    GroupLabel("资料")
                    SettingsCard {
                        SettingRow(
                            icon = LiuliIcons.Edit,
                            label = "设置备注",
                            value = contact?.remark?.takeIf { it.isNotBlank() } ?: "未设置",
                            onClick = { remarkOpen = true }
                        )
                    }
                }

                item(key = "chatPrefs") {
                    GroupLabel("聊天")
                    SettingsCard {
                        SwitchRow(
                            icon = LiuliIcons.Mute,
                            label = "消息免打扰",
                            subtitle = if (conv == null) "还没有聊天记录" else "新消息不再计数，只留一个小灰点",
                            checked = conv?.muted == true,
                            enabled = conv != null
                        ) { on ->
                            val current = conv
                            if (current != null) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Svc.store.saveConversation(current.copy(muted = on))
                                    }
                                    notice = if (on) "已开启消息免打扰" else "已关闭消息免打扰"
                                }
                            }
                        }
                        GlassDivider(inset = 48.dp)
                        SwitchRow(
                            icon = LiuliIcons.Pin,
                            label = "置顶聊天",
                            subtitle = if (conv == null) "还没有聊天记录" else "会话始终排在最前",
                            checked = conv?.pinned == true,
                            enabled = conv != null
                        ) { on ->
                            val current = conv
                            if (current != null) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Svc.store.saveConversation(current.copy(pinned = on))
                                    }
                                    notice = if (on) "已置顶会话" else "已取消置顶"
                                }
                            }
                        }
                    }
                }

                item(key = "privacy") {
                    GroupLabel("隐私")
                    SettingsCard {
                        SwitchRow(
                            icon = LiuliIcons.Lock,
                            label = "加入黑名单",
                            // This used to promise "本地标记，未同步对端：对方仍能发
                            // 消息", which stopped being true once the transport
                            // started enforcing the block. Blocking is one-way
                            // and real now: you can still send to them, and
                            // everything they send is dropped before it reaches
                            // the store — their client shows it as rejected.
                            subtitle = "对方发来的消息会被直接丢弃，他那边会显示发送失败；你仍然可以发给他",
                            checked = blocked,
                            enabled = true
                        ) { on ->
                            blocked = on
                            setContactBlocked(context, deviceId, on)
                            notice = if (on) "已加入黑名单，对方将发不进来" else "已移出黑名单"
                        }
                    }
                }

                item(key = "actions") {
                    GroupLabel("操作")
                    SettingsCard {
                        if (conv != null) {
                            SettingRow(
                                icon = LiuliIcons.Delete,
                                label = "清空聊天记录",
                                value = "仅本机",
                                showChevron = false
                            ) { confirmClear = true }
                        }
                        SettingRow(
                            icon = LiuliIcons.Logout,
                            label = "删除好友",
                            tint = LiuliColors.Danger,
                            labelColor = LiuliColors.Danger,
                            showChevron = false
                        ) { confirmDelete = true }
                    }
                }
            }
        },
        overlay = {
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = display,
                    subtitle = contact?.address?.takeIf { it.isNotBlank() }?.let { "蓝牙地址 $it" }
                        ?: "本机联系人",
                    onBack = onBack
                )
                Spacer(Modifier.weight(1f))
            }

            GlassConfirmDialog(
                visible = confirmDelete,
                title = "删除好友",
                message = "「$display」会从通讯录移除，已有的聊天记录仍然保留在本机。",
                confirmText = "删除",
                destructive = true,
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { Svc.store.deleteContact(deviceId) }
                        onBack()
                    }
                }
            )

            GlassConfirmDialog(
                visible = confirmClear,
                title = "清空聊天记录",
                message = "与「$display」的消息和媒体文件都会从本机删除，对方不受影响。",
                confirmText = "清空",
                destructive = true,
                onDismiss = { confirmClear = false },
                onConfirm = {
                    confirmClear = false
                    val target = conv
                    if (target != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Svc.store.messages(target.id, limit = 5000).forEach { message ->
                                    message.attachment?.let { attachment ->
                                        runCatching { Svc.media.deleteAttachmentFiles(attachment) }
                                    }
                                }
                                Svc.store.clearHistory(target.id)
                            }
                            notice = "聊天记录已清空"
                        }
                    }
                }
            )

            RemarkSheet(
                visible = remarkOpen,
                initial = contact?.remark.orEmpty(),
                fallback = contact?.name.orEmpty(),
                onDismiss = { remarkOpen = false },
                onConfirm = { remark ->
                    remarkOpen = false
                    val target = contact
                    if (target != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val updated = target.copy(remark = remark)
                                Svc.store.saveContact(updated)
                                // The chat list reads the contact book first, but
                                // older builds froze the name into the
                                // conversation — refresh it as well.
                                Svc.store.conversations()
                                    .filter { it.kind == ConvKind.DIRECT && it.peerId == deviceId }
                                    .forEach { Svc.store.saveConversation(it.copy(title = updated.display)) }
                            }
                            notice = "备注已保存"
                            profile = withContext(Dispatchers.IO) { readProfile(deviceId) }
                        }
                    }
                }
            )

            ProfileNotice(message = notice, onGone = { notice = null })
        }
    )
}

/** Everything the page reads in one IO pass. */
private data class ContactProfileData(
    val contact: Contact?,
    val conv: Conversation?,
    val online: Boolean
)

private fun readProfile(deviceId: String): ContactProfileData {
    val store = Svc.store
    val contact = store.contact(deviceId)
    val conv = store.conversations().firstOrNull {
        it.kind == ConvKind.DIRECT && (it.peerId == deviceId || it.id == deviceId)
    }
    val online = Svc.engine.isConnected(deviceId) ||
        Svc.engine.peers.value.any {
            it.connected && (it.deviceId == deviceId || it.address == contact?.address)
        }
    return ContactProfileData(contact, conv, online)
}

/** Grey group label above a card. */
@Composable
private fun GroupLabel(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp)
    ) {
        Text(
            text,
            color = LiuliColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .background(LiuliColors.Surface, RoundedCornerShape(Radii.card)),
        content = content
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    tint: Color = LiuliColors.Accent,
    labelColor: Color = LiuliColors.TextPrimary,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            label,
            color = labelColor,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                value,
                color = LiuliColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showChevron) {
            Icon(
                LiuliIcons.ChevronRight,
                contentDescription = null,
                tint = LiuliColors.Separator,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) LiuliColors.Accent else LiuliColors.TextTertiary,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = LiuliColors.TextPrimary,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                subtitle,
                color = LiuliColors.TextTertiary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        // No switch at all without a conversation to write to: a control that
        // cannot do anything would be exactly the fake switch we must not ship.
        if (enabled) {
            GlassSwitch(checked = checked, onCheckedChange = onChange)
        } else {
            Text(
                "—",
                color = LiuliColors.TextTertiary,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

/** Small glass editor for the remark. */
@Composable
private fun RemarkSheet(
    visible: Boolean,
    initial: String,
    fallback: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(visible) { mutableStateOf(initial) }

    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Text(
            "设置备注",
            color = LiuliColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(10.dp))
        GlassSearchField(
            value = value,
            onValueChange = { value = it.take(24) },
            placeholder = fallback.ifBlank { "备注名" }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "备注只保存在本机，会话列表会立即显示新名字",
            color = LiuliColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall
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
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** Dark rounded toast for the one-off confirmations on this page. */
@Composable
private fun ProfileNotice(message: String?, onGone: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            onGone()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (message != null) {
            Box(
                Modifier
                    .padding(horizontal = 48.dp)
                    .padding(bottom = 150.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------------------------------------------------------- local block

private const val BlockPrefs = "liuli_blocked"

/**
 * 拉黑 lives in local preferences, but its effect does not stop here.
 *
 * `Di` hands this list to the transport, which drops anything an inbound
 * connection sends from a blocked device and tells the sender it was rejected —
 * so the switch is one-way enforcement, not a label. This file only owns the
 * storage; the policy is in `bt/Router`.
 */
internal fun isContactBlocked(context: Context, deviceId: String): Boolean =
    context.getSharedPreferences(BlockPrefs, Context.MODE_PRIVATE)
        .getStringSet("ids", emptySet())
        ?.contains(deviceId) == true

/** The whole local block list, so a list screen can tag its rows in one read. */
internal fun blockedContacts(context: Context): Set<String> =
    context.getSharedPreferences(BlockPrefs, Context.MODE_PRIVATE)
        .getStringSet("ids", emptySet())
        .orEmpty()
        .toSet()

internal fun setContactBlocked(context: Context, deviceId: String, blocked: Boolean) {
    val prefs = context.getSharedPreferences(BlockPrefs, Context.MODE_PRIVATE)
    val current = prefs.getStringSet("ids", emptySet()).orEmpty().toMutableSet()
    if (blocked) current.add(deviceId) else current.remove(deviceId)
    prefs.edit().putStringSet("ids", current).apply()
}
