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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.seedOf
import com.liuli.btchat.ui.components.ContactRow
import com.liuli.btchat.ui.components.LiuliScaffold
import com.liuli.btchat.ui.components.RowAvatarSize
import com.liuli.btchat.ui.components.RowSeparatorInset
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassEmptyState
import com.liuli.btchat.ui.glass.GlassPill
import com.liuli.btchat.ui.glass.GlassPrimaryButton
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.GroupAvatar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发起群聊。
 *
 * WeChat's create-group page: a white row for the group name, a white row with
 * the live mosaic preview, the picked members as removable chips, and a member
 * list with the tick on the left. The only sticky chrome is the white action bar
 * with the solid green 创建 button, which stays disabled until the group has a
 * name and at least one member.
 *
 * Everything runs through [com.liuli.btchat.core.ChatEngine.createGroup].
 */
@Composable
fun GroupCreateScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    val revision by Svc.store.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }

    LaunchedEffect(revision) {
        val loaded = withContext(Dispatchers.IO) { Svc.store.contacts() }
        contacts = loaded
        // Drop selections whose contact disappeared while the screen was open.
        selected.removeAll { id -> loaded.none { it.deviceId == id } }
    }

    val picked = remember(contacts, selected.toList()) {
        contacts.filter { it.deviceId in selected }
    }
    val visible = remember(contacts, query) {
        if (query.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.display.contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.address.contains(query, ignoreCase = true)
            }
        }
    }
    val seeds = picked.map { it.avatarSeed }
    val canCreate = name.isNotBlank() && picked.isNotEmpty() && !creating

    LiuliScaffold(
        content = {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentPadding = PaddingValues(top = 50.dp, bottom = 96.dp)
            ) {
                item(key = "name") {
                    Row(
                        Modifier
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .fillMaxWidth()
                            .background(LiuliColors.Surface, RoundedCornerShape(Radii.card))
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "群名称",
                            color = LiuliColors.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.size(16.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            if (name.isEmpty()) {
                                Text(
                                    "给群聊起个名字",
                                    color = LiuliColors.TextTertiary,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            CompositionLocalProvider(
                                LocalTextStyle provides MaterialTheme.typography.bodyLarge
                            ) {
                                BasicTextField(
                                    value = name,
                                    onValueChange = { name = it.take(24) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = LiuliColors.TextPrimary
                                    ),
                                    cursorBrush = SolidColor(LiuliColors.Accent),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                item(key = "preview") {
                    Row(
                        Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .background(LiuliColors.Surface, RoundedCornerShape(Radii.card))
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        GroupAvatar(seeds = seeds, size = 56.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                name.ifBlank { "未命名群聊" },
                                color = if (name.isBlank()) {
                                    LiuliColors.TextTertiary
                                } else {
                                    LiuliColors.TextPrimary
                                },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                            Text(
                                if (picked.isEmpty()) {
                                    "至少选择 1 位联系人"
                                } else {
                                    "${picked.size + 1} 位成员（含你）"
                                },
                                color = if (picked.isEmpty()) LiuliColors.Warn else LiuliColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "群头像由成员头像自动拼成",
                                color = LiuliColors.TextTertiary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                if (picked.isNotEmpty()) {
                    item(key = "chips") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(LiuliColors.Bg)
                                .padding(vertical = 10.dp)
                        ) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                itemsIndexed(
                                    picked,
                                    key = { index, item -> "chip-${item.deviceId}-$index" }
                                ) { _, contact ->
                                    GlassPill(
                                        text = contact.display,
                                        selected = true,
                                        onClick = { selected.remove(contact.deviceId) },
                                        leading = {
                                            Icon(
                                                LiuliIcons.Close,
                                                contentDescription = "移除",
                                                tint = LiuliColors.TextOnAccent,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "search") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(LiuliColors.Bg)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "选择联系人（已选 ${picked.size}）",
                            color = LiuliColors.TextTertiary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                if (visible.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().background(LiuliColors.Surface)) {
                            GlassEmptyState(
                                icon = LiuliIcons.Contacts,
                                title = if (contacts.isEmpty()) "通讯录还是空的" else "没有匹配的联系人",
                                message = if (contacts.isEmpty()) {
                                    "先回到通讯录，用「发现设备」搜索并保存一位联系人，再回来建群"
                                } else {
                                    "换个名字、设备 ID 或蓝牙地址试试"
                                },
                                action = {
                                    if (contacts.isEmpty()) {
                                        GlassPrimaryButton(onClick = onBack) {
                                            Text(
                                                "先去添加联系人",
                                                color = LiuliColors.TextOnAccent,
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
                    visible,
                    key = { index, item -> "m-${item.deviceId}-$index" }
                ) { _, contact ->
                    ContactRow(
                        name = contact.display,
                        seed = contact.avatarSeed,
                        remark = contact.name.takeIf { contact.remark.isNotBlank() },
                        subtitle = contact.address.ifBlank { "点击选中，再点一次取消" },
                        selected = contact.deviceId in selected,
                        selectable = true,
                        leadingCheck = true,
                        avatar = {
                            // A person, not a group: one gradient disc with the
                            // initial. GroupAvatar would pad the list with three
                            // filler seeds and draw a mosaic.
                            GlassAvatar(
                                name = contact.display,
                                seed = contact.avatarSeed,
                                size = RowAvatarSize,
                                glassSheen = false
                            )
                        },
                        onClick = {
                            if (contact.deviceId in selected) {
                                selected.remove(contact.deviceId)
                            } else {
                                selected.add(contact.deviceId)
                            }
                        }
                    )
                }

                item(key = "tail") { GlassDivider(inset = RowSeparatorInset) }
            }
        },
        overlay = {
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = "发起群聊",
                    subtitle = if (picked.isEmpty()) "选好成员就能开聊" else "已选 ${picked.size} 位联系人",
                    onBack = onBack
                )
                Spacer(Modifier.weight(1f))

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(LiuliColors.Surface)
                        // The action bar is the bottom-most element here (there
                        // is no tab bar on a second-level page), so it owns the
                        // navigation-bar inset itself.
                        .navigationBarsPadding()
                ) {
                    GlassDivider()
                    if (error != null) {
                        Text(
                            error.orEmpty(),
                            color = LiuliColors.Danger,
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 16.dp, end = 16.dp)
                        )
                    }
                    GlassPrimaryButton(
                        onClick = {
                            val members = picked
                            if (members.isEmpty() || name.isBlank()) return@GlassPrimaryButton
                            creating = true
                            error = null
                            scope.launch {
                                val conv = withContext(Dispatchers.IO) {
                                    runCatching {
                                        Svc.engine.createGroup(
                                            name = name.trim(),
                                            avatarSeed = seedOf(name.trim(), members.size),
                                            members = members
                                        )
                                    }.getOrNull()
                                }
                                creating = false
                                if (conv != null) {
                                    onCreated(conv.id)
                                } else {
                                    error = "创建失败：请确认已选择了成员，且蓝牙服务已启动"
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        enabled = canCreate
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    creating -> "正在创建…"
                                    name.isBlank() -> "先给群聊起个名字"
                                    picked.isEmpty() -> "至少选择 1 位联系人"
                                    else -> "创建群聊（${picked.size + 1} 人）"
                                },
                                color = LiuliColors.TextOnAccent,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    )
}
