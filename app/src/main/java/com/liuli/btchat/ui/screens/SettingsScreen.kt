package com.liuli.btchat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.bt.SelfTest
import com.liuli.btchat.core.LinkState
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.core.Wire
import com.liuli.btchat.media.MediaLayout
import com.liuli.btchat.ui.components.LiuliScaffold
import com.liuli.btchat.ui.components.ProfileSheet
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassBottomNav
import com.liuli.btchat.ui.glass.GlassButton
import com.liuli.btchat.ui.glass.GlassConfirmDialog
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassPrimaryButton
import com.liuli.btchat.ui.glass.GlassSheet
import com.liuli.btchat.ui.glass.GlassSwitch
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.NavItem
import com.liuli.btchat.ui.glass.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置。**底部栏的 Tab 根页之一**，所以 `onBack` 默认是 null —— Tab 根页不显示
 * 返回箭头。
 *
 * The iOS/WeChat grouped list: one white 12dp-rounded card per section, 52dp
 * rows with a leading icon, a value or a chevron on the right, hairline
 * separators inset to the row's text, and 12dp of page background between
 * groups. There are no gradient cards and no fake controls — every switch
 * writes straight through [com.liuli.btchat.core.SettingsApi.edit].
 *
 * Storage figures are computed on `Dispatchers.IO` from the app's own media
 * directories and the message counts of every conversation; the 实验室 section
 * runs the Bluetooth self-test and shows its report in a monospace panel.
 */
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onOpenChats: (() -> Unit)? = null,
    onOpenContacts: (() -> Unit)? = null,
    onOpenStarred: (() -> Unit)? = null,
    onOpenChat: ((String) -> Unit)? = null,
    onOpenMe: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val prefs by Svc.settings.flow.collectAsStateWithLifecycle()
    val link by Svc.engine.link.collectAsStateWithLifecycle()
    val revision by Svc.store.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var storage by remember { mutableStateOf<StorageStats?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var clearDone by remember { mutableStateOf(false) }
    var selftestRunning by remember { mutableStateOf(false) }
    var selftestReport by remember { mutableStateOf<String?>(null) }
    var selftestOpen by remember { mutableStateOf(false) }
    var starredOpen by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        storage = withContext(Dispatchers.IO) { readStorage(context) }
    }

    val version = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }

    // 「我的收藏」 opens as a pushed page when the shell wires onOpenStarred, and
    // in place otherwise, so the row is never a dead tap.
    if (starredOpen && onOpenStarred == null) {
        BackHandler { starredOpen = false }
        StarredScreen(
            onBack = { starredOpen = false },
            onOpenChat = { convId -> onOpenChat?.invoke(convId) }
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
                // 我的资料 belongs to the 我的 tab, not here — having it in both
                // places was the thing the user objected to. 设置 keeps the
                // things that are about the app rather than about you.
                item(key = "chat") {
                    GroupLabel("聊天与提醒")
                    SettingsCard {
                        SwitchRow(
                            icon = LiuliIcons.Download,
                            label = "自动接收媒体",
                            subtitle = "图片和视频直接接收；语音始终自动接收",
                            checked = prefs.autoAcceptMedia,
                            onChange = { on -> Svc.settings.edit { it.copy(autoAcceptMedia = on) } }
                        )
                        GlassDivider(inset = 16.dp)
                        SwitchRow(
                            icon = LiuliIcons.Bell,
                            label = "新消息提示音",
                            checked = prefs.soundOn,
                            onChange = { on -> Svc.settings.edit { it.copy(soundOn = on) } }
                        )
                        GlassDivider(inset = 16.dp)
                        SwitchRow(
                            icon = LiuliIcons.Speed,
                            label = "震动提醒",
                            checked = prefs.vibrateOn,
                            onChange = { on -> Svc.settings.edit { it.copy(vibrateOn = on) } }
                        )
                    }
                }

                item(key = "storage") {
                    val stats = storage
                    GroupLabel("存储")
                    SettingsCard {
                        ValueRow("媒体文件", stats?.let { TimeFmt.size(it.mediaBytes) } ?: "统计中…")
                        GlassDivider(inset = 16.dp)
                        ValueRow("聊天记录", stats?.let { "${it.messageCount} 条" } ?: "统计中…")
                        GlassDivider(inset = 16.dp)
                        ValueRow("会话", stats?.let { "${it.conversationCount} 个" } ?: "统计中…")
                        GlassDivider(inset = 16.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlassButton(
                                onClick = {
                                    scope.launch {
                                        storage = withContext(Dispatchers.IO) { readStorage(context) }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    "重新统计",
                                    color = LiuliColors.TextPrimary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            if (clearDone) {
                                Text(
                                    "已清空记录与媒体文件",
                                    color = LiuliColors.Success,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        GlassDivider(inset = 16.dp)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { confirmClear = true }
                                .heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "清空聊天记录",
                                color = LiuliColors.Danger,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                item(key = "lab") {
                    GroupLabel("实验室")
                    SettingsCard {
                        SettingRow(
                            icon = LiuliIcons.Verified,
                            label = if (selftestRunning) "自检中…" else "运行蓝牙自检",
                            subtitle = "在本机跑一遍完整协议，不需要第二台手机",
                            onClick = {
                                if (selftestRunning) return@SettingRow
                                selftestRunning = true
                                clearDone = false
                                scope.launch {
                                    val report = runCatching {
                                        SelfTest.run(context.applicationContext)
                                    }.getOrElse { error ->
                                        "自检未能完成：${error.message ?: error::class.java.simpleName}"
                                    }
                                    selftestRunning = false
                                    selftestReport = report
                                    selftestOpen = true
                                    storage = withContext(Dispatchers.IO) { readStorage(context) }
                                }
                            }
                        )
                        if (selftestReport != null && !selftestRunning) {
                            val summary = summarizeReport(selftestReport.orEmpty())
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selftestOpen = true }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    "最近一次：$summary",
                                    color = if (summary.contains("失败")) LiuliColors.Danger else LiuliColors.Success,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }

                // 聊天与提醒 and 我的收藏 now live under the 我的 tab, so they are
                // deliberately absent here — the two tabs do not overlap.
                item(key = "about") {
                    GroupLabel("关于")
                    SettingsCard {
                        ValueRow("版本", version)
                        GlassDivider(inset = 16.dp)
                        ValueRow("协议版本", "Wire v${Wire.PROTO_VERSION}")
                        GlassDivider(inset = 16.dp)
                        ValueRow("蓝牙状态", linkLabel(link.state))
                        GlassDivider(inset = 16.dp)
                        ValueRow("已连接设备", "${link.connections} 台")
                        GlassDivider(inset = 16.dp)
                        ValueRow("本机设备 ID", prefs.myDeviceId.ifBlank { "正在生成…" })
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp)) {
                        Text(
                            "琉璃让两台安卓手机通过蓝牙直接聊天，消息、图片和视频都不经过服务器。",
                            color = LiuliColors.TextTertiary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        },
        overlay = {
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = "设置",
                    subtitle = prefs.myName.ifBlank { "琉璃 · 本机直连" },
                    onBack = onBack
                )
                Spacer(Modifier.weight(1f))
                GlassBottomNav(
                    items = TabItems,
                    selected = 3,
                    onSelect = { index ->
                        when (index) {
                            0 -> onOpenChats?.invoke()
                            1 -> onOpenContacts?.invoke()
                            2 -> onOpenMe?.invoke()
                        }
                    },
                    badgeFor = { if (it == 0) storage?.unread ?: 0 else 0 }
                )
            }

            GlassConfirmDialog(
                visible = confirmClear,
                title = "清空聊天记录",
                message = "所有会话的消息记录和它们携带的图片、视频文件都会从本机删除。这个操作无法撤销。",
                confirmText = "清空",
                destructive = true,
                onDismiss = { confirmClear = false },
                onConfirm = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val store = Svc.store
                            store.conversations().forEach { conv ->
                                // 附件和消息一起清：媒体文件的删除走 MediaVault，
                                // 因为导入时的 transferId 和传输时的 id 并不是同一个。
                                store.messages(conv.id, limit = 5000).forEach { message ->
                                    message.attachment?.let { attachment ->
                                        runCatching { Svc.media.deleteAttachmentFiles(attachment) }
                                    }
                                }
                                store.clearHistory(conv.id)
                            }
                        }
                        storage = withContext(Dispatchers.IO) { readStorage(context) }
                        clearDone = true
                    }
                }
            )

            SelftestSheet(
                visible = selftestOpen,
                report = selftestReport,
                onDismiss = { selftestOpen = false },
                onCopy = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(
                        ClipData.newPlainText("琉璃蓝牙自检报告", selftestReport.orEmpty())
                    )
                }
            )

            ProfileSheet(
                visible = profileOpen,
                prefs = prefs,
                adapterName = link.adapterName,
                conversationCount = storage?.conversationCount ?: 0,
                contactCount = storage?.contactCount ?: 0,
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

/** One white rounded group card. */
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
private fun Chevron() {
    Icon(
        LiuliIcons.ChevronRight,
        contentDescription = null,
        tint = LiuliColors.Separator,
        modifier = Modifier.size(16.dp)
    )
}

/** Icon + label row that opens something. */
@Composable
private fun SettingRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
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
            tint = LiuliColors.Accent,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = LiuliColors.TextPrimary,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = LiuliColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Chevron()
    }
}

/** Label on the left, read-only value on the right. */
@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = LiuliColors.TextPrimary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            color = LiuliColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(160.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
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
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = LiuliColors.Accent,
            modifier = Modifier.size(20.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = LiuliColors.TextPrimary,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = LiuliColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        GlassSwitch(checked = checked, onCheckedChange = onChange)
    }
}

/** The self-test report, scrollable and monospace, with a copy action. */
@Composable
private fun SelftestSheet(
    visible: Boolean,
    report: String?,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    GlassSheet(visible = visible, onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                LiuliIcons.Verified,
                contentDescription = null,
                tint = LiuliColors.Accent,
                modifier = Modifier.size(18.dp)
            )
            Text(
                "蓝牙自检报告",
                color = LiuliColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                summarizeReport(report.orEmpty()),
                color = LiuliColors.TextTertiary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .background(LiuliColors.SurfaceAlt, RoundedCornerShape(Radii.field))
                .verticalScroll(rememberScrollState())
                .padding(13.dp)
        ) {
            Text(
                report.orEmpty(),
                color = LiuliColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassButton(
                onClick = onCopy,
                modifier = Modifier.weight(1f)
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "复制报告",
                        color = LiuliColors.TextPrimary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
            GlassPrimaryButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f)
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "完成",
                        color = LiuliColors.TextOnAccent,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

private data class StorageStats(
    val messageCount: Int,
    val conversationCount: Int,
    val contactCount: Int,
    val unread: Int,
    val starred: Int,
    val mediaBytes: Long
)

/** The four tab-bar entries, shared by every tab root. */
private val TabItems = tabItems

private fun linkLabel(state: LinkState): String = when (state) {
    LinkState.CONNECTED -> "已连接"
    LinkState.READY -> "就绪"
    LinkState.SCANNING -> "搜索中"
    LinkState.CONNECTING -> "连接中"
    LinkState.UNAUTHORIZED -> "缺少权限"
    LinkState.ERROR -> "异常"
    LinkState.OFF -> "未开启"
}

/**
 * One-line summary of a [SelfTest] report.
 *
 * The report ends with a `结果: …` line, so that is the cheapest thing to read;
 * the `[通过] / [失败]` tally is the fallback for a report that was cut short.
 */
private fun summarizeReport(report: String): String {
    if (report.isBlank()) return "还没有运行过"
    val result = report.lineSequence()
        .lastOrNull { it.trimStart().startsWith("结果:") }
        ?.substringAfter("结果:")
        ?.substringBefore("SELFTEST")
        ?.trim()
    if (!result.isNullOrBlank()) return result
    val pass = Regex("\\[通过\\]").findAll(report).count()
    val fail = Regex("\\[失败\\]").findAll(report).count()
    return "$pass 项通过 · $fail 项失败"
}

/** Counts messages and measures the app's payload + thumbnail directories. */
private fun readStorage(context: Context): StorageStats {
    val store = Svc.store
    val conversations = store.conversations()
    val messages = conversations.sumOf { store.messages(it.id, limit = 5000).size }
    val bytes = listOf(
        MediaLayout.mediaDir(context),
        MediaLayout.thumbDir(context)
    ).sumOf { dir ->
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    return StorageStats(
        messageCount = messages,
        conversationCount = conversations.size,
        contactCount = store.contacts().size,
        unread = store.totalUnread(),
        starred = store.starredCount(),
        mediaBytes = bytes
    )
}
