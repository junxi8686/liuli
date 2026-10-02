package com.liuli.btchat.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.BuildConfig
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.Wire
import com.liuli.btchat.ui.components.LiuliScaffold
import com.liuli.btchat.ui.components.ProfileSheet
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassBottomNav
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassMode
import com.liuli.btchat.ui.glass.GlassSwitch
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.NavItem
import com.liuli.btchat.ui.glass.Radii

/**
 * 「我的」 — the fourth tab.
 *
 * Owns the identity card, the collections, the **visual effect setting** and the
 * everyday switches. 设置 keeps the connection/storage/diagnostics side, so the
 * two tabs do not overlap.
 */
@Composable
fun MeScreen(
    onOpenStarred: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val prefs by Svc.settings.flow.collectAsStateWithLifecycle()
    val revision by Svc.store.revision.collectAsStateWithLifecycle()
    val unread = remember(revision) { Svc.store.totalUnread() }
    val conversations = remember(revision) { Svc.store.conversations().size }
    val contacts = remember(revision) { Svc.store.contacts().size }

    // 我的资料 lives here now; 设置 no longer carries a copy of it.
    var profileOpen by remember { mutableStateOf(false) }

    ProfileSheet(
        visible = profileOpen,
        prefs = prefs,
        // Without these two the sheet fell back to its `= 0` defaults, so the
        // 会话 / 联系人 tiles read 0 / 0 no matter how much was on the phone —
        // which looks exactly like data loss. Every other entry point into this
        // sheet passes real numbers; this one was the only one that did not.
        conversationCount = conversations,
        contactCount = contacts,
        onDismiss = { profileOpen = false },
        onApply = { name, status, seed ->
            Svc.settings.edit { it.copy(myName = name, myStatus = status, myAvatarSeed = seed) }
            profileOpen = false
        }
    )

    LiuliScaffold(
        content = {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentPadding = PaddingValues(top = 58.dp, bottom = 100.dp)
            ) {
                item(key = "me") {
                    MeHeader(
                        name = prefs.myName.ifBlank { "我" },
                        status = prefs.myStatus,
                        seed = prefs.myAvatarSeed,
                        deviceId = prefs.myDeviceId,
                        onEdit = { profileOpen = true }
                    )
                }

                item(key = "profile") {
                    GroupLabel("我的资料")
                    SettingsCard {
                        SettingRow(
                            icon = LiuliIcons.Me,
                            label = "昵称",
                            value = prefs.myName.ifBlank { "还没有昵称" }
                        ) { profileOpen = true }
                        GlassDivider(inset = 16.dp)
                        SettingRow(
                            icon = LiuliIcons.Emoji,
                            label = "状态签名",
                            value = prefs.myStatus.ifBlank { "在蓝牙上" }
                        ) { profileOpen = true }
                    }
                }

                item(key = "collections") {
                    GroupLabel("我的")
                    SettingsCard {
                        SettingRow(
                            icon = LiuliIcons.Star,
                            label = "我的收藏",
                            value = "${Svc.store.starredCount()} 条"
                        ) { onOpenStarred() }
                    }
                }

                // 视觉效果的三个档位已经撤掉：用户要的就是干净的白底界面，
                // 磨砂只在弹层上留一点，没有可调的必要；留着一排开关反而
                // 让人以为调了会有什么区别。效果强度仍然可以被设置文件里的
                // 值驱动（老用户升上来不会突变），只是不再有 UI 入口。
                //
                // 深色模式同理：不再给「跟随系统/浅色/深色」的选择，直接
                // 跟随系统 —— 这也是绝大多数人想要的默认行为。

                // 版本就摆在这里，一眼能看到自己装的是哪一版 —— 反馈问题时
                // 「你装的是哪版」是最常被问到的第一句话。
                item(key = "version") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp, bottom = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "琉璃 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                            color = LiuliColors.TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }

                // 关于 / 连接诊断 deliberately stays in 设置 so the two tabs do
                // not show the same thing twice.
            }
        },
        overlay = {
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(title = "我的", subtitle = prefs.myName.ifBlank { "未命名" })
                Spacer(Modifier.weight(1f))
                GlassBottomNav(
                    items = tabItems,
                    selected = 2,
                    onSelect = { index ->
                        when (index) {
                            0 -> onOpenChats()
                            1 -> onOpenContacts()
                            3 -> onOpenSettings()
                        }
                    },
                    badgeFor = { if (it == 0) unread else 0 }
                )
            }
        }
    )
}

/** The four peer tabs, in one place so every root screen agrees. */
internal val tabItems: List<NavItem> = listOf(
    NavItem(LiuliIcons.Chats, "会话"),
    NavItem(LiuliIcons.Contacts, "通讯录"),
    NavItem(LiuliIcons.Me, "我的"),
    NavItem(LiuliIcons.Settings, "设置")
)

@Composable
private fun MeHeader(
    name: String,
    status: String,
    seed: Int,
    deviceId: String,
    onEdit: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(LiuliColors.Surface)
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GlassAvatar(name = name, seed = seed, size = 64.dp, glassSheen = false)
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = LiuliColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                status.ifBlank { "在蓝牙上" },
                color = LiuliColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (deviceId.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "琉璃号 ${deviceId.take(13)}",
                    color = LiuliColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Icon(
            LiuliIcons.ChevronRight,
            contentDescription = null,
            tint = LiuliColors.Separator,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * 主题三档：跟随系统 / 浅色 / 深色。
 *
 * Follow-the-phone is the default, so a user who has dark mode on the system
 * gets a dark app without opening this screen at all.
 */
@Composable
private fun ThemeModePicker(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "外观",
            color = LiuliColors.TextPrimary,
            style = MaterialTheme.typography.bodyLarge
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (mode, label) ->
                val active = mode == selected
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .background(
                            if (active) LiuliColors.Accent else LiuliColors.SurfaceAlt,
                            RoundedCornerShape(Radii.field)
                        )
                        .clickable { onSelect(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (active) Color.White else LiuliColors.TextSecondary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassModePicker(selected: GlassMode, onSelect: (GlassMode) -> Unit) {
    val options = listOf(
        GlassMode.Liquid to "液态玻璃",
        GlassMode.Blur to "高斯模糊",
        GlassMode.None to "全部关闭"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, label) ->
            val active = mode == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(
                        if (active) LiuliColors.Accent else LiuliColors.SurfaceAlt,
                        RoundedCornerShape(Radii.field)
                    )
                    .clickable { onSelect(mode) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (active) androidx.compose.ui.graphics.Color.White else LiuliColors.TextSecondary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun StrengthSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    val alpha = if (enabled) 1f else 0.4f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "强度",
                color = LiuliColors.TextPrimary.copy(alpha = alpha),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${(local * 100).toInt()}%",
                color = LiuliColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Slider(
            value = local,
            onValueChange = { v ->
                local = v
                onChange(v)
            },
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = LiuliColors.Accent,
                activeTrackColor = LiuliColors.Accent
            )
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 6.dp),
        color = LiuliColors.TextTertiary,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(LiuliColors.Surface, RoundedCornerShape(Radii.card)),
        content = content
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
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
        Icon(icon, contentDescription = null, tint = LiuliColors.Accent, modifier = Modifier.size(20.dp))
        Text(
            label,
            color = LiuliColors.TextPrimary,
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
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = LiuliColors.Accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = LiuliColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = LiuliColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        GlassSwitch(checked = checked, onCheckedChange = onChange)
    }
}
