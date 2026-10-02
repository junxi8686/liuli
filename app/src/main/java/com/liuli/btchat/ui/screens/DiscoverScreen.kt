package com.liuli.btchat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuli.btchat.bt.BtPermissions
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.LinkState
import com.liuli.btchat.core.LinkStatus
import com.liuli.btchat.core.Peer
import com.liuli.btchat.core.PeerSource
import com.liuli.btchat.core.Svc
import com.liuli.btchat.ui.components.LiuliScaffold
import com.liuli.btchat.ui.components.PeerRow
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.GlassButton
import com.liuli.btchat.ui.glass.GlassDivider
import com.liuli.btchat.ui.glass.GlassEmptyState
import com.liuli.btchat.ui.glass.GlassIconButton
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassPrimaryButton
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
 * 蓝牙发现。
 *
 * The whole Bluetooth lifecycle in one WeChat-shaped page: a white status card
 * with the two actions that matter (搜索 / 刷新配对), a white 本机 card, then
 * flat device rows grouped into 已连接 / 已配对 / 附近. Only the top bar and the
 * action sheet carry material.
 *
 * Tapping a peer calls [com.liuli.btchat.core.ChatEngine.connect] and then waits
 * for the link to come up before opening the conversation, so a pair that fails
 * is reported in a dark toast instead of dropping the user into an empty chat.
 */
@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val context = LocalContext.current
    val link by Svc.engine.link.collectAsStateWithLifecycle()
    val peers by Svc.engine.peers.collectAsStateWithLifecycle()
    val prefs by Svc.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var connectingKey by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var peerSheet by remember { mutableStateOf<Peer?>(null) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val ok = granted.values.any { it }
        toast = if (ok) "权限已更新，正在重新检测蓝牙" else "仍未获得附近设备权限，蓝牙无法扫描"
        scope.launch { withContext(Dispatchers.IO) { Svc.engine.refreshBonded() } }
    }

    // Bonded devices are the most useful list here, so ask for them on open.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Svc.engine.refreshBonded() }
    }

    val scanning = link.state == LinkState.SCANNING
    val connected = peers.filter { it.connected }
    val bonded = peers.filter {
        !it.connected && (it.source == PeerSource.BONDED || it.source == PeerSource.SAVED)
    }
    val nearby = peers.filter { !it.connected && it.source == PeerSource.DISCOVERED }

    fun openChatWith(peer: Peer) {
        scope.launch {
            val conv = withContext(Dispatchers.IO) {
                runCatching { Svc.engine.ensureDirectConversation(peer) }.getOrNull()
            }
            if (conv != null) onOpenChat(conv.id) else toast = "无法打开会话：设备信息不完整"
        }
    }

    fun connectTo(peer: Peer) {
        if (peer.connected) {
            openChatWith(peer)
            return
        }
        scope.launch {
            connectingKey = peer.key
            val started = withContext(Dispatchers.IO) {
                runCatching { Svc.engine.connect(peer) }.getOrDefault(false)
            }
            if (!started) {
                connectingKey = null
                toast = "无法连接：请确认蓝牙已打开并已授予权限"
                return@launch
            }
            val established = awaitLink(peer)
            connectingKey = null
            if (established) {
                val live = Svc.engine.peers.value.firstOrNull { it.key == peer.key } ?: peer
                openChatWith(live)
            } else {
                toast = "连接「${peer.name.ifBlank { peer.address }}」失败，请确认对方已打开琉璃"
            }
        }
    }

    LiuliScaffold(
        content = {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
                contentPadding = PaddingValues(top = 50.dp, bottom = 40.dp)
            ) {
                item(key = "status") {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        BluetoothCard(
                            link = link,
                            scanning = scanning,
                            pairedCount = bonded.size,
                            connectedCount = connected.size,
                            onScan = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (scanning) {
                                            Svc.engine.stopScan()
                                        } else {
                                            Svc.engine.refreshBonded()
                                            Svc.engine.startScan()
                                        }
                                    }
                                }
                            },
                            onRefresh = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Svc.engine.refreshBonded() }
                                    toast = "已刷新本机已配对设备"
                                }
                            },
                            onGrant = {
                                val missing = BtPermissions.missing(context)
                                if (missing.isEmpty()) {
                                    toast = "权限已经齐全，正在重新扫描"
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            Svc.engine.refreshBonded()
                                            Svc.engine.startScan()
                                        }
                                    }
                                } else {
                                    permissions.launch(missing.toTypedArray())
                                }
                            }
                        )
                    }
                }

                item(key = "local") {
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        LocalInfoCard(
                            adapterName = link.adapterName,
                            deviceId = prefs.myDeviceId,
                            discoverable = prefs.discoverable,
                            onDiscoverable = { on ->
                                Svc.settings.edit { it.copy(discoverable = on) }
                                toast = if (on) "已允许附近设备发现本机" else "已关闭被发现"
                            },
                            onCopy = { value ->
                                copyText(context, "琉璃 设备 ID", value)
                                toast = "设备 ID 已复制"
                            }
                        )
                    }
                }

                if (connected.isNotEmpty()) {
                    item(key = "connectedHeader") { SectionHeader("已连接（${connected.size}）") }
                    itemsIndexed(
                        connected,
                        key = { index, peer -> "on-${peer.address}-${peer.deviceId}-$index" }
                    ) { _, peer ->
                        PeerRow(
                            peer = peer,
                            connecting = connectingKey == peer.key,
                            onClick = { connectTo(peer) },
                            onLongClick = { peerSheet = peer }
                        )
                    }
                }

                if (bonded.isNotEmpty()) {
                    item(key = "bondedHeader") { SectionHeader("已配对设备（${bonded.size}）") }
                    itemsIndexed(
                        bonded,
                        key = { index, peer -> "bd-${peer.address}-${peer.deviceId}-$index" }
                    ) { _, peer ->
                        PeerRow(
                            peer = peer,
                            connecting = connectingKey == peer.key,
                            onClick = { connectTo(peer) },
                            onLongClick = { peerSheet = peer }
                        )
                    }
                }

                if (nearby.isNotEmpty()) {
                    item(key = "nearbyHeader") { SectionHeader("附近设备（${nearby.size}）") }
                    itemsIndexed(
                        nearby,
                        key = { index, peer -> "nb-${peer.address}-${peer.deviceId}-$index" }
                    ) { _, peer ->
                        PeerRow(
                            peer = peer,
                            connecting = connectingKey == peer.key,
                            onClick = { connectTo(peer) },
                            onLongClick = { peerSheet = peer }
                        )
                    }
                }

                if (peers.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().background(LiuliColors.Surface)) {
                            GlassEmptyState(
                                icon = if (scanning) LiuliIcons.Discover else LiuliIcons.Bluetooth,
                                title = if (scanning) "正在搜索附近设备…" else "还没有发现设备",
                                message = if (scanning) {
                                    "保持这个页面打开，附近开启琉璃的安卓设备会自动出现"
                                } else {
                                    "点上面的「搜索附近设备」，或确认手机的蓝牙已打开"
                                },
                                action = {
                                    if (!scanning) {
                                        GlassPrimaryButton(
                                            onClick = {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        Svc.engine.refreshBonded()
                                                        Svc.engine.startScan()
                                                    }
                                                }
                                            }
                                        ) {
                                            Text(
                                                "搜索附近设备",
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
            }
        },
        overlay = {
            Column(Modifier.fillMaxSize()) {
                GlassTopBar(
                    title = "发现",
                    subtitle = link.adapterName.ifBlank { "蓝牙设备与配对" },
                    onBack = onBack,
                    trailing = {
                        GlassIconButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Svc.engine.refreshBonded() }
                                    toast = "已刷新设备列表"
                                }
                            },
                            size = 38.dp,
                            bordered = false
                        ) {
                            Icon(
                                LiuliIcons.Refresh,
                                contentDescription = "刷新设备",
                                tint = LiuliColors.TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )
                Spacer(Modifier.weight(1f))
            }

            GlassActionSheet(
                visible = peerSheet != null,
                onDismiss = { peerSheet = null },
                title = peerSheet?.name?.ifBlank { peerSheet?.address },
                actions = peerSheet?.let { peer ->
                    buildList {
                        add(
                            SheetAction(
                                icon = LiuliIcons.Chat,
                                label = if (peer.connected) "打开会话" else "连接并聊天",
                                description = peer.address.ifBlank { peer.deviceId }
                            ) {
                                peerSheet = null
                                connectTo(peer)
                            }
                        )
                        if (peer.address.isNotBlank()) {
                            add(
                                SheetAction(
                                    icon = LiuliIcons.Copy,
                                    label = "复制蓝牙地址",
                                    description = peer.address
                                ) {
                                    copyText(context, "蓝牙地址", peer.address)
                                    peerSheet = null
                                    toast = "地址已复制"
                                }
                            )
                        }
                        add(
                            SheetAction(
                                icon = LiuliIcons.Contacts,
                                label = "保存为联系人",
                                description = "之后不用再重新扫描"
                            ) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        Svc.store.saveContact(
                                            Contact(
                                                deviceId = peer.deviceId.ifBlank { peer.address },
                                                name = peer.name.ifBlank { peer.address },
                                                address = peer.address,
                                                avatarSeed = peer.avatarSeed,
                                                lastSeen = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                    peerSheet = null
                                    toast = "已把「${peer.name.ifBlank { peer.address }}」存进通讯录"
                                }
                            }
                        )
                        if (peer.connected) {
                            add(
                                SheetAction(
                                    icon = LiuliIcons.Logout,
                                    label = "断开连接",
                                    description = "随时可以重新连接",
                                    danger = true
                                ) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { Svc.engine.disconnect(peer.address) }
                                        peerSheet = null
                                        toast = "已断开 ${peer.name.ifBlank { peer.address }}"
                                    }
                                }
                            )
                        }
                    }
                } ?: emptyList()
            )

            DiscoveryToast(message = toast, onGone = { toast = null })
        }
    )
}

/** The status card: adapter health, an animated scan ring and the two actions. */
@Composable
private fun BluetoothCard(
    link: LinkStatus,
    scanning: Boolean,
    pairedCount: Int,
    connectedCount: Int,
    onScan: () -> Unit,
    onRefresh: () -> Unit,
    onGrant: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "scan")
    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    val (icon, tint) = statusVisual(link.state)
    val title = when (link.state) {
        LinkState.CONNECTED -> "已连接 $connectedCount 台设备"
        LinkState.READY -> "蓝牙就绪"
        LinkState.SCANNING -> "正在搜索附近设备…"
        LinkState.CONNECTING -> "正在连接…"
        LinkState.UNAUTHORIZED -> "缺少蓝牙权限"
        LinkState.ERROR -> "蓝牙出现异常"
        LinkState.OFF -> "蓝牙未开启"
    }
    val detail = when (link.state) {
        LinkState.CONNECTED -> "消息会直接送到对方，不经过服务器"
        LinkState.READY -> "已配对 $pairedCount 台设备，可以开始搜索"
        LinkState.SCANNING -> "附近开启琉璃的设备会自动出现"
        LinkState.CONNECTING -> link.message.ifBlank { "正在协商安全通道" }
        LinkState.UNAUTHORIZED -> "需要「附近的设备」权限才能搜索和配对"
        LinkState.ERROR -> link.message.ifBlank { "请关闭再打开蓝牙后重试" }
        LinkState.OFF -> "请在系统设置里打开蓝牙，然后回来重试"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(LiuliColors.Surface, RoundedCornerShape(Radii.card))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(56.dp)
                        .scale(if (scanning) pulse else 1f)
                        .background(tint.copy(alpha = 0.12f), CircleShape)
                )
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier
                        .size(28.dp)
                        .then(if (scanning) Modifier.rotate(spin) else Modifier)
                )
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    title,
                    color = LiuliColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    detail,
                    color = LiuliColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (link.state == LinkState.UNAUTHORIZED) {
                GlassPrimaryButton(onClick = onGrant, modifier = Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "授予权限",
                            color = LiuliColors.TextOnAccent,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            } else {
                GlassPrimaryButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            if (scanning) "停止搜索" else "搜索附近设备",
                            color = LiuliColors.TextOnAccent,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
            GlassButton(
                onClick = onRefresh,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Text(
                    "刷新配对",
                    color = LiuliColors.TextPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/** Bluetooth name, own device id and the discoverable switch. */
@Composable
private fun LocalInfoCard(
    adapterName: String,
    deviceId: String,
    discoverable: Boolean,
    onDiscoverable: (Boolean) -> Unit,
    onCopy: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LiuliColors.Surface, RoundedCornerShape(Radii.card))
    ) {
        InfoRow(
            icon = LiuliIcons.Bluetooth,
            label = "蓝牙名称",
            value = adapterName.ifBlank { "读取中…" }
        )
        GlassDivider(inset = 16.dp)
        InfoRow(
            icon = LiuliIcons.QrCode,
            label = "我的设备 ID",
            value = deviceId.ifBlank { "正在生成…" },
            actionLabel = "复制",
            onAction = { if (deviceId.isNotBlank()) onCopy(deviceId) }
        )
        GlassDivider(inset = 16.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                LiuliIcons.Discover,
                contentDescription = null,
                tint = LiuliColors.Accent,
                modifier = Modifier.size(20.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "可被发现",
                    color = LiuliColors.TextPrimary,
                    style = MaterialTheme.typography.bodyLarge
                )
                // This used to be a switch reading "附近设备可以看到本机". It wrote
                // a preference that **nothing ever read**, so flipping it changed
                // nothing — and its default was `true`, meaning a fresh install
                // claimed the phone was discoverable before the user touched
                // anything. Classic Bluetooth discoverability is a *system*
                // setting with a two-minute window (`ACTION_REQUEST_DISCOVERABLE`);
                // an app cannot grant or revoke it. Saying so beats a dead switch.
                Text(
                    "由系统蓝牙在限时窗口内控制，琉璃本身不能开关",
                    color = LiuliColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = onAction != null) { onAction?.invoke() }
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
            modifier = Modifier.width(150.dp),
        )
        if (actionLabel != null) {
            Text(
                actionLabel,
                color = LiuliColors.Accent,
                style = MaterialTheme.typography.labelMedium
            )
        } else {
            Icon(
                LiuliIcons.ChevronRight,
                contentDescription = null,
                tint = LiuliColors.Separator,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** Grey strip between two white sections. */
@Composable
private fun SectionHeader(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(LiuliColors.Bg)
            .padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
    ) {
        Text(
            text,
            color = LiuliColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** Dark rounded toast, the WeChat way of reporting something that failed. */
@Composable
private fun DiscoveryToast(message: String?, onGone: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(3200)
            onGone()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 3 },
            exit = fadeOut(tween(140))
        ) {
            Box(
                Modifier
                    .padding(horizontal = 48.dp)
                    .systemBarsPadding()
                    .padding(bottom = 96.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    message.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Icon + tint for the adapter state; composable because the palette is. */
@Composable
private fun statusVisual(state: LinkState): Pair<ImageVector, Color> = when (state) {
    LinkState.CONNECTED -> LiuliIcons.BluetoothOn to LiuliColors.Success
    LinkState.READY -> LiuliIcons.Bluetooth to LiuliColors.Accent
    LinkState.SCANNING -> LiuliIcons.Discover to LiuliColors.Cyan
    LinkState.CONNECTING -> LiuliIcons.Speed to LiuliColors.Warn
    LinkState.UNAUTHORIZED -> LiuliIcons.Lock to LiuliColors.Danger
    LinkState.ERROR -> LiuliIcons.Error to LiuliColors.Danger
    LinkState.OFF -> LiuliIcons.BluetoothOff to LiuliColors.TextTertiary
}

/** Waits up to ~9 s for a peer to report itself connected. */
private suspend fun awaitLink(peer: Peer): Boolean {
    repeat(36) {
        val live = Svc.engine.peers.value.any {
            it.connected && (it.key == peer.key || (peer.address.isNotBlank() && it.address == peer.address))
        }
        if (live) return true
        delay(250)
    }
    return false
}

private fun copyText(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}
