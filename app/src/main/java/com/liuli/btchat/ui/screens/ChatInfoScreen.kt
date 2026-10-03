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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.Member
import com.liuli.btchat.core.Role
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TimeFmt
import com.liuli.btchat.ui.components.Walkie
import com.liuli.btchat.data.ChatPrefs
import com.liuli.btchat.media.launchImagePicker
import com.liuli.btchat.media.rememberImagePicker
import com.liuli.btchat.ui.glass.ChatBackgrounds
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.GlassAvatar
import com.liuli.btchat.ui.glass.GlassPrimaryButton
import com.liuli.btchat.ui.glass.GlassConfirmDialog
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassSheet
import com.liuli.btchat.ui.glass.GlassStage
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
 * 聊天信息二级页（点聊天页右上角「…」进来）。
 *
 * 微信的「聊天信息」是一整页，不是弹出菜单，所以这里用 [GlassStage] 做一个真正的
 * 二级页面：白卡片 + 发丝线 + 平涂行，玻璃只留在顶栏。
 *
 * 内容按会话类型分：
 *  * 单聊：对方资料行（点头像/名字 → 对方主页）、查找聊天记录、消息免打扰、置顶聊天；
 *  * 群聊：群名称（改名）、全部群成员（展开名单，点头像进个人主页）、消息免打扰、置顶；
 *  * 危险区：清空聊天记录（连同落盘的附件一起删）、退出群聊（群聊时）。
 *
 * @param convId 会话 id。
 * @param onBack 返回聊天页。
 * @param onOpenContact 进对方/成员主页。
 * @param onSearch 进「查找聊天记录」二级页。
 * @param onLeft 退出群聊成功后回列表。
 */
@Composable
fun ChatInfoScreen(
    convId: String,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    onSearch: () -> Unit,
    onLeft: () -> Unit
) {
    val revision by Svc.store.revision.collectAsState()
    val conversation = remember(revision, convId) { Svc.store.conversation(convId) }
    val isGroup = conversation?.kind == ConvKind.GROUP
    val iAmOwner = conversation?.iAmOwner == true
    val members = remember(revision, convId) {
        if (isGroup) Svc.store.members(convId) else emptyList()
    }
    val contact = remember(revision, convId) {
        conversation?.peerId?.takeIf { it.isNotBlank() }?.let { Svc.store.contact(it) }
    }
    val density = LocalDensity.current

    var notice by remember { mutableStateOf<String?>(null) }
    var clearOpen by remember { mutableStateOf(false) }
    var leaveOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var membersOpen by remember { mutableStateOf(false) }
    // 群成员管理
    var inviteOpen by remember { mutableStateOf(false) }
    var memberMenuFor by remember { mutableStateOf<Member?>(null) }
    var kickTarget by remember { mutableStateOf<Member?>(null) }
    var ownerLeaveOpen by remember { mutableStateOf(false) }
    var announcementOpen by remember { mutableStateOf(false) }
    val myId = remember { Svc.settings.ensureIdentity().myDeviceId }
    var announcementEditOpen by remember { mutableStateOf(false) }
    var nicknameOpen by remember { mutableStateOf(false) }
    var adminTarget by remember { mutableStateOf<Member?>(null) }
    var ownerTarget by remember { mutableStateOf<Member?>(null) }
    var muteTarget by remember { mutableStateOf<Member?>(null) }
    // 群主或管理员才能管理（只读成员看到的是公告和禁言标记，没有入口）
    val iAmAdmin = remember(members, myId) {
        members.firstOrNull { it.deviceId == myId }?.role?.let { it == Role.OWNER || it == Role.ADMIN }
            ?: false
    }

    // 邀请面板：只在打开时读一次联系人，再过滤掉已经在群里的
    var inviteSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var inviteContacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    val inviteCandidates = remember(inviteContacts, members) {
        val inGroup = members.map { it.deviceId }.toSet() + myId
        inviteContacts.filter { it.deviceId.isNotBlank() && it.deviceId !in inGroup }
    }
    LaunchedEffect(inviteOpen) {
        if (inviteOpen) {
            inviteContacts = withContext(Dispatchers.IO) {
                runCatching { Svc.store.contacts() }.getOrDefault(emptyList())
            }
        } else {
            inviteSelection = emptySet()
        }
    }

    // Per-chat wallpaper + reminder, kept out of the message schema.
    val bgRevision by ChatPrefs.revision.collectAsState()
    var backgroundOpen by remember { mutableStateOf(false) }
    var chatNotify by remember(convId) { mutableStateOf(ChatPrefs.notify(convId)) }
    val bgPreset = remember(convId, bgRevision) { ChatPrefs.background(convId) }
    val bgImage = remember(convId, bgRevision) { ChatPrefs.backgroundImage(convId) }
    val backgroundLabel = if (bgImage != null) "自定义图片" else ChatBackgrounds.nameOf(bgPreset)

    // 对讲机：模式开关（每会话）+ 自动播放开关（全局）都持久化在 ChatPrefs；
    // bgRevision 就是 ChatPrefs.revision，开关一写就会刷新。
    val walkieOn = remember(convId, bgRevision) { Walkie.enabled(convId) }
    val walkieAutoPlay = remember(bgRevision) { Walkie.autoPlayVoice() }

    val scope = rememberCoroutineScope()
    val imagePicker = rememberImagePicker { uri ->
        scope.launch {
            val imported = withContext(Dispatchers.IO) { Svc.media.importImage(uri) }
            if (imported != null) {
                ChatPrefs.setBackgroundImage(convId, imported.filePath)
                notice = "聊天背景已更换"
            } else {
                notice = "这张图片读不出来"
            }
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2000)
            notice = null
        }
    }

    val statusTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val safeBottom = with(density) { WindowInsets.safeDrawing.getBottom(density).toDp() }
    val title = when {
        isGroup -> conversation?.title?.takeIf { it.isNotBlank() } ?: "群聊"
        else -> contact?.display?.takeIf { it.isNotBlank() && it != "我" }
            ?: conversation?.title?.takeIf { it.isNotBlank() }
            ?: "聊天信息"
    }

    GlassStage(
        background = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LiuliColors.Bg)
            )
        },
        content = {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        top = statusTop + 56.dp,
                        bottom = safeBottom + 24.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ---------------------------------------------------- 头像区
                if (isGroup) {
                    GroupHeader(
                        members = members,
                        myDeviceId = myId,
                        ownerCanInvite = true,
                        onOpenContact = onOpenContact,
                        onInvite = { inviteOpen = true }
                    )
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Radii.card))
                                .clickable(
                                    enabled = conversation?.peerId?.isNotBlank() == true,
                                    onClick = { onOpenContact(conversation?.peerId.orEmpty()) }
                                )
                                .padding(vertical = 14.dp, horizontal = 24.dp)
                        ) {
                            GlassAvatar(
                                name = title,
                                seed = conversation?.avatarSeed ?: 0,
                                size = 64.dp,
                                glassSheen = false
                            )
                            Text(
                                title,
                                color = LiuliColors.TextPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            Text(
                                contact?.deviceId?.takeIf { it.isNotBlank() } ?: conversation?.peerId.orEmpty(),
                                color = LiuliColors.TextTertiary,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                // ---------------------------------------------------- 通用
                Card {
                    InfoRow(
                        label = "查找聊天记录",
                        icon = LiuliIcons.Search,
                        onClick = onSearch
                    )
                    Hairline()
                    InfoRow(
                        label = "聊天背景",
                        value = backgroundLabel,
                        icon = LiuliIcons.Palette,
                        onClick = { backgroundOpen = true }
                    )
                }

                // ---------------------------------------------------- 通话
                // 群聊只给语音（微信的群视频是另一套，不在本次范围），视频入口在群聊里不出现。
                Card {
                    InfoRow(
                        label = if (isGroup) "语音通话（多人）" else "语音通话",
                        icon = LiuliIcons.Mic,
                        onClick = {
                            CallBridge.startCall(convId, false)?.let { notice = it }
                        }
                    )
                    if (!isGroup) {
                        Hairline()
                        InfoRow(
                            label = "视频通话",
                            icon = LiuliIcons.Video,
                            onClick = {
                                CallBridge.startCall(convId, true)?.let { notice = it }
                            }
                        )
                    }
                }

                // ---------------------------------------------------- 对讲机
                // 定义：按住说话 → 对方自动接收 → 收到自动播放。自动外放是隐私敏感行为，
                // 所以「收到自动播放」必须能关（默认开），这里是唯一的开关位置。
                Card {
                    SwitchRow(
                        label = "对讲机模式",
                        subtitle = "按住即说，对方自动接收并播放",
                        checked = walkieOn,
                        onCheckedChange = { on -> Walkie.setEnabled(convId, on) }
                    )
                    Hairline()
                    SwitchRow(
                        label = "收到对讲自动播放",
                        subtitle = "关掉后只提示、不自动外放",
                        checked = walkieAutoPlay,
                        onCheckedChange = { on -> Walkie.setAutoPlayVoice(on) }
                    )
                }

                // ---------------------------------------------------- 提醒
                Card {
                    SwitchRow(
                        label = "新消息提醒",
                        subtitle = "关闭后这个会话来消息不响也不震",
                        checked = chatNotify,
                        onCheckedChange = { on ->
                            ChatPrefs.setNotify(convId, on)
                            chatNotify = on
                        }
                    )
                    Hairline()
                    SwitchRow(
                        label = "消息免打扰",
                        checked = conversation?.muted == true,
                        onCheckedChange = { on ->
                            val c = Svc.store.conversation(convId) ?: return@SwitchRow
                            Svc.store.saveConversation(c.copy(muted = on))
                        }
                    )
                    Hairline()
                    SwitchRow(
                        label = "置顶聊天",
                        checked = conversation?.pinned == true,
                        onCheckedChange = { on ->
                            val c = Svc.store.conversation(convId) ?: return@SwitchRow
                            Svc.store.saveConversation(c.copy(pinned = on))
                        }
                    )
                }

                // ---------------------------------------------------- 群公告 / 全员禁言 / 群昵称
                //
                // 群主/管理员：公告能编辑、全员禁言能开关；
                // 普通成员：**只读**（公告能看、禁言标记能看），不给点了没反应的入口。
                val announcement = conversation?.announcement.orEmpty()
                val muteAll = conversation?.muteAll == true
                if (isGroup) {
                    Card {
                        InfoRow(
                            label = "群公告",
                            value = announcement.ifBlank {
                                if (iAmAdmin) "点击发布群公告" else "暂无公告"
                            },
                            onClick = {
                                if (iAmAdmin) {
                                    announcementEditOpen = true
                                } else if (announcement.isNotBlank()) {
                                    announcementOpen = true
                                } else {
                                    notice = "群里还没有公告"
                                }
                            }
                        )
                        if (announcement.isNotBlank()) {
                            Hairline()
                            InfoRow(
                                label = "查看公告全文",
                                value = conversation?.announcementAt?.takeIf { it > 0L }
                                    ?.let { TimeFmt.clock(it) }
                                    .orEmpty(),
                                onClick = { announcementOpen = true }
                            )
                        }
                        if (iAmAdmin) {
                            Hairline()
                            SwitchRow(
                                label = "全员禁言",
                                subtitle = "开启后只有群主和管理员能发言",
                                checked = muteAll,
                                onCheckedChange = { on ->
                                    runCatching { Svc.engine.setGroupMuteAll(convId, on) }
                                    notice = if (on) "已开启全员禁言" else "已关闭全员禁言"
                                }
                            )
                        } else if (muteAll) {
                            Hairline()
                            InfoRow(
                                label = "全员禁言",
                                value = "已开启（只有群主和管理员能发言）",
                                onClick = { notice = "全员禁言进行中：只有群主和管理员能发言" }
                            )
                        }
                        // 群昵称：所有成员都能改自己的（改了对方看到的就是新名字）
                        Hairline()
                        val myNick = members.firstOrNull { it.deviceId == myId }?.name.orEmpty()
                        InfoRow(
                            label = "我在本群的昵称",
                            value = myNick.ifBlank { "未设置（用本机昵称）" },
                            onClick = { nicknameOpen = true }
                        )
                    }
                }

                // ---------------------------------------------------- 群聊
                if (isGroup) {
                    Card {
                        InfoRow(
                            label = "群聊名称",
                            value = conversation?.title.orEmpty(),
                            onClick = { renameOpen = true }
                        )
                        Hairline()
                        InfoRow(
                            label = "全部群成员",
                            value = "${members.size} 人",
                            onClick = { membersOpen = !membersOpen }
                        )
                        Hairline()
                        InfoRow(
                            label = "群二维码",
                            value = "本机离线生成",
                            onClick = { notice = "二维码功能稍后开放" }
                        )
                    }

                    if (membersOpen) {
                        Card {
                            if (members.isEmpty()) {
                                Text(
                                    "暂时拿不到成员名单，等群主同步后就有了。",
                                    color = LiuliColors.TextSecondary,
                                    fontSize = 13.5.sp,
                                    modifier = Modifier.padding(14.dp)
                                )
                            } else {
                                members.forEachIndexed { index, member ->
                                    if (index > 0) Hairline()
                                    MemberRow(
                                        member = member,
                                        self = member.deviceId == myId,
                                        onClick = { onOpenContact(member.deviceId) },
                                        // 群主/管理员才给管理入口；自己没有管理菜单
                                        onManage = if (
                                            iAmOwner && member.deviceId != myId
                                        ) {
                                            { memberMenuFor = member }
                                        } else {
                                            null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ---------------------------------------------------- 危险区
                Card {
                    InfoRow(
                        label = "清空聊天记录",
                        danger = true,
                        onClick = { clearOpen = true }
                    )
                    if (isGroup) {
                        Hairline()
                        InfoRow(
                            label = "退出群聊",
                            danger = true,
                            onClick = {
                                // 微信的规矩：群主不能直接退群，必须先转让（本工程暂时
                                // 只能解散）。这里给出明确提示，不假装能退。
                                if (iAmOwner && members.size > 1) {
                                    ownerLeaveOpen = true
                                } else {
                                    leaveOpen = true
                                }
                            }
                        )
                    }
                }

                Text(
                    "琉璃 · 本机直连，不经过服务器",
                    color = LiuliColors.TextTertiary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        },
        overlay = {
            Box(Modifier.fillMaxSize()) {
                GlassTopBar(title = "聊天信息", centered = true, onBack = onBack)

                notice?.let {
                    Text(
                        it,
                        color = Color.White,
                        fontSize = 13.5.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = statusTop + 62.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xE6000000))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                // ---- 群公告全文（只读）----
                GlassSheet(
                    visible = announcementOpen,
                    onDismiss = { announcementOpen = false }
                ) {
                    Text(
                        "群公告",
                        color = LiuliColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                    )
                    Text(
                        conversation?.announcement.orEmpty(),
                        color = LiuliColors.TextPrimary,
                        fontSize = 14.5.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    val by = conversation?.announcementBy.orEmpty()
                    val at = conversation?.announcementAt ?: 0L
                    if (by.isNotBlank() || at > 0L) {
                        Text(
                            buildString {
                                val author = members.firstOrNull { it.deviceId == by }?.name
                                    ?: by.take(8)
                                if (by.isNotBlank()) append("由 $author 发布")
                                if (at > 0L) {
                                    if (isNotEmpty()) append(" · ")
                                    append(TimeFmt.clock(at))
                                }
                            },
                            color = LiuliColors.TextTertiary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 10.dp, start = 4.dp)
                        )
                    }
                    Text(
                        "编辑公告需要等群管理接口就绪（bt 侧实现中）。",
                        color = LiuliColors.TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 14.dp, start = 4.dp, bottom = 4.dp)
                    )
                }

                // ---- 成员管理菜单（群主）----
                val menuMember = memberMenuFor
                GlassActionSheet(
                    visible = menuMember != null,
                    onDismiss = { memberMenuFor = null },
                    title = menuMember?.name?.ifBlank { menuMember.deviceId.take(8) } ?: "",
                    actions = buildList {
                        if (menuMember != null) {
                            add(
                                SheetAction(
                                    icon = LiuliIcons.Contacts,
                                    label = "查看资料",
                                    onClick = {
                                        memberMenuFor = null
                                        onOpenContact(menuMember.deviceId)
                                    }
                                )
                            )
                            // ---- 群主/管理员操作（每一项都有二次确认）----
                            add(
                                SheetAction(
                                    icon = LiuliIcons.Mute,
                                    label = if (menuMember.muted) "解除禁言" else "禁言此人",
                                    description = if (menuMember.muted) {
                                        "恢复 TA 在群里发言"
                                    } else {
                                        "禁言后 TA 不能在群里发言"
                                    },
                                    onClick = {
                                        memberMenuFor = null
                                        muteTarget = menuMember
                                    }
                                )
                            )
                            if (iAmOwner) {
                                val isAdmin = menuMember.role == Role.ADMIN
                                add(
                                    SheetAction(
                                        icon = LiuliIcons.Verified,
                                        label = if (isAdmin) "取消管理员" else "设为管理员",
                                        description = if (isAdmin) {
                                            "收回管理权限（公告、禁言、踢人）"
                                        } else {
                                            "可以发布公告、禁言他人、移出成员"
                                        },
                                        onClick = {
                                            memberMenuFor = null
                                            adminTarget = menuMember
                                        }
                                    )
                                )
                                add(
                                    SheetAction(
                                        icon = LiuliIcons.Swap,
                                        label = "转让群主",
                                        description = "转让后你变成普通成员，无法撤销",
                                        onClick = {
                                            memberMenuFor = null
                                            ownerTarget = menuMember
                                        }
                                    )
                                )
                            }
                            add(
                                SheetAction(
                                    icon = LiuliIcons.Delete,
                                    label = "移出群聊",
                                    description = "对方会收到成员变更通知",
                                    onClick = {
                                        memberMenuFor = null
                                        kickTarget = menuMember
                                    }
                                )
                            )
                        }
                    }
                )

                // ---- 禁言确认 ----
                val mute = muteTarget
                GlassConfirmDialog(
                    visible = mute != null,
                    title = if (mute?.muted == true) "解除禁言" else "禁言此人",
                    message = if (mute?.muted == true) {
                        "恢复「${mute?.name?.ifBlank { mute.deviceId.take(8) } ?: ""}」在群里发言？"
                    } else {
                        "「${mute?.name?.ifBlank { mute.deviceId.take(8) } ?: ""}」将不能在群里发言，" +
                            "私聊不受影响。"
                    },
                    destructive = mute?.muted != true,
                    confirmText = if (mute?.muted == true) "解除" else "禁言",
                    onDismiss = { muteTarget = null },
                    onConfirm = {
                        val target = muteTarget
                        muteTarget = null
                        if (target != null) {
                            val next = !target.muted
                            runCatching { Svc.engine.setMemberMuted(convId, target.deviceId, next) }
                            notice = if (next) {
                                "已禁言 ${target.name.ifBlank { target.deviceId.take(8) }}"
                            } else {
                                "已解除禁言"
                            }
                        }
                    }
                )

                // ---- 管理员确认 ----
                val admin = adminTarget
                GlassConfirmDialog(
                    visible = admin != null,
                    title = if (admin?.role == Role.ADMIN) "取消管理员" else "设为管理员",
                    message = if (admin?.role == Role.ADMIN) {
                        "收回「${admin?.name?.ifBlank { admin.deviceId.take(8) } ?: ""}」的管理权限？"
                    } else {
                        "「${admin?.name?.ifBlank { admin.deviceId.take(8) } ?: ""}」将可以发布公告、" +
                            "禁言他人、移出成员。"
                    },
                    destructive = admin?.role == Role.ADMIN,
                    confirmText = if (admin?.role == Role.ADMIN) "取消" else "设为管理员",
                    onDismiss = { adminTarget = null },
                    onConfirm = {
                        val target = adminTarget
                        adminTarget = null
                        if (target != null) {
                            val next = target.role != Role.ADMIN
                            runCatching { Svc.engine.setGroupAdmin(convId, target.deviceId, next) }
                            notice = if (next) "已设为管理员" else "已取消管理员"
                        }
                    }
                )

                // ---- 转让群主确认（最危险的操作，文案要说清不可撤销）----
                val nextOwner = ownerTarget
                GlassConfirmDialog(
                    visible = nextOwner != null,
                    title = "转让群主",
                    message = "把群主转让给「${nextOwner?.name?.ifBlank { nextOwner.deviceId.take(8) } ?: ""}」？" +
                        "转让后你成为普通成员，对方获得解散群聊、转让群主等全部权限，" +
                        "**此操作无法撤销**。",
                    destructive = true,
                    confirmText = "确认转让",
                    onDismiss = { ownerTarget = null },
                    onConfirm = {
                        val target = ownerTarget
                        ownerTarget = null
                        if (target != null) {
                            runCatching { Svc.engine.transferGroupOwner(convId, target.deviceId) }
                            notice = "群主已转让给 ${target.name.ifBlank { target.deviceId.take(8) }}"
                        }
                    }
                )

                // ---- 踢人确认 ----
                val kick = kickTarget
                GlassConfirmDialog(
                    visible = kick != null,
                    title = "移出群聊",
                    message = "把「${kick?.name?.ifBlank { kick.deviceId.take(8) } ?: ""}」移出这个群？" +
                        "对方会收到成员变更通知，历史消息仍在。",
                    destructive = true,
                    confirmText = "移出",
                    onDismiss = { kickTarget = null },
                    onConfirm = {
                        val target = kickTarget
                        kickTarget = null
                        if (target != null) {
                            Svc.engine.removeGroupMember(convId, target.deviceId)
                            notice = "已把 ${target.name.ifBlank { target.deviceId.take(8) }} 移出群聊"
                        }
                    }
                )

                // ---- 群主退群提示（微信规则：先转让才能退；本工程只能解散）----
                GlassConfirmDialog(
                    visible = ownerLeaveOpen,
                    title = "群主不能直接退群",
                    message = "微信的规则是群主需要先把群主转让给别人。本工程暂时没有转让功能，" +
                        "所以只能解散群聊 —— 解散后所有成员都会收到通知，聊天记录各自保留。",
                    destructive = true,
                    confirmText = "解散群聊",
                    onDismiss = { ownerLeaveOpen = false },
                    onConfirm = {
                        ownerLeaveOpen = false
                        Svc.engine.dissolveGroup(convId)
                        notice = "群聊已解散"
                        onBack()
                    }
                )

                // ---- 邀请成员（立刻可选，不是摆设）----
                GlassSheet(
                    visible = inviteOpen,
                    onDismiss = { inviteOpen = false }
                ) {
                    Text(
                        "邀请成员",
                        color = LiuliColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                    Text(
                        "点一下选中，可以多选；已经在群里的联系人不会出现。",
                        color = LiuliColors.TextTertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                    )
                    if (inviteCandidates.isEmpty()) {
                        Text(
                            "没有可以邀请的联系人。先到「通讯录」配对新的设备。",
                            color = LiuliColors.TextSecondary,
                            fontSize = 13.5.sp,
                            modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp)
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            inviteCandidates.forEach { candidate ->
                                val checked = candidate.deviceId in inviteSelection
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radii.field))
                                        .clickable {
                                            inviteSelection = if (checked) {
                                                inviteSelection - candidate.deviceId
                                            } else {
                                                inviteSelection + candidate.deviceId
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    GlassAvatar(
                                        name = candidate.display,
                                        seed = candidate.avatarSeed,
                                        size = 34.dp,
                                        glassSheen = false
                                    )
                                    Text(
                                        candidate.display,
                                        color = LiuliColors.TextPrimary,
                                        fontSize = 14.5.sp,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 10.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        if (checked) LiuliIcons.Checked else LiuliIcons.Check,
                                        contentDescription = null,
                                        tint = if (checked) {
                                            LiuliColors.Accent
                                        } else {
                                            LiuliColors.TextTertiary.copy(alpha = 0.35f)
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    GlassPrimaryButton(
                        onClick = {
                            val picked = inviteCandidates.filter {
                                it.deviceId in inviteSelection
                            }
                            inviteOpen = false
                            inviteSelection = emptySet()
                            if (picked.isEmpty()) return@GlassPrimaryButton
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    picked.forEach { Svc.engine.addGroupMember(convId, it) }
                                }
                                notice = "已邀请 ${picked.size} 位成员"
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                if (inviteSelection.isEmpty()) {
                                    "选择联系人"
                                } else {
                                    "邀请 ${inviteSelection.size} 位成员"
                                },
                                color = LiuliColors.TextOnAccent,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                GlassConfirmDialog(
                    visible = clearOpen,
                    title = "清空聊天记录",
                    message = "本机上这个会话的消息会全部删除，对方不受影响。",
                    destructive = true,
                    confirmText = "清空",
                    onDismiss = { clearOpen = false },
                    onConfirm = {
                        clearOpen = false
                        // 附件文件按附件路径删（导入 id 与传输 id 不是同一个）。
                        Svc.store.messages(convId).mapNotNull { it.attachment }.forEach { att ->
                            runCatching { Svc.media.deleteAttachmentFiles(att) }
                        }
                        Svc.store.clearHistory(convId)
                        notice = "已清空聊天记录"
                    }
                )

                GlassSheet(
                    visible = backgroundOpen,
                    onDismiss = { backgroundOpen = false }
                ) {
                    Text(
                        "设置聊天背景",
                        color = LiuliColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ChatBackgrounds.presets.forEachIndexed { index, (presetName, color) ->
                            Column(
                                Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .clip(RoundedCornerShape(Radii.field))
                                        .background(color)
                                        .clickable {
                                            ChatPrefs.setBackground(convId, index)
                                            ChatPrefs.setBackgroundImage(convId, null)
                                            backgroundOpen = false
                                            notice = "聊天背景：$presetName"
                                        }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(Radii.card))
                            .background(LiuliColors.SurfaceAlt)
                            .clickable {
                                backgroundOpen = false
                                imagePicker.launchImagePicker()
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            LiuliIcons.Image,
                            contentDescription = null,
                            tint = LiuliColors.Accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "从相册选择图片",
                            color = LiuliColors.TextPrimary,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (bgImage != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(Radii.card))
                                .clickable {
                                    ChatPrefs.setBackgroundImage(convId, null)
                                    backgroundOpen = false
                                    notice = "已恢复默认背景"
                                }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                LiuliIcons.Refresh,
                                contentDescription = null,
                                tint = LiuliColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "恢复默认背景",
                                color = LiuliColors.TextSecondary,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                GlassConfirmDialog(
                    visible = leaveOpen,
                    title = "退出群聊",
                    message = "退出后不再接收这个群的消息，群主仍可再次邀请你。",
                    destructive = true,
                    confirmText = "退出",
                    onDismiss = { leaveOpen = false },
                    onConfirm = {
                        leaveOpen = false
                        runCatching { Svc.engine.leaveGroup(convId) }
                        onLeft()
                    }
                )

                GlassTextPrompt(
                    visible = announcementEditOpen,
                    title = "群公告",
                    initial = conversation?.announcement.orEmpty(),
                    hint = "写点什么给全体成员看（留空即清除公告）",
                    onDismiss = { announcementEditOpen = false },
                    onConfirm = { text ->
                        announcementEditOpen = false
                        runCatching { Svc.engine.updateGroupAnnouncement(convId, text.trim()) }
                        notice = if (text.isBlank()) "已清除群公告" else "群公告已更新"
                    }
                )

                GlassTextPrompt(
                    visible = nicknameOpen,
                    title = "我在本群的昵称",
                    initial = members.firstOrNull { it.deviceId == myId }?.name.orEmpty(),
                    hint = "群昵称（留空则用本机昵称）",
                    onDismiss = { nicknameOpen = false },
                    onConfirm = { text ->
                        nicknameOpen = false
                        val trimmed = text.trim()
                        runCatching { Svc.engine.setMyGroupNickname(convId, trimmed) }
                        notice = "群昵称已更新，对方会看到新名字"
                    }
                )

                GlassTextPrompt(
                    visible = renameOpen,
                    title = "修改群名",
                    initial = conversation?.title.orEmpty(),
                    hint = "输入新的群名",
                    onDismiss = { renameOpen = false },
                    onConfirm = { name ->
                        renameOpen = false
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty() && trimmed != conversation?.title) {
                            runCatching { Svc.engine.renameGroup(convId, trimmed) }
                            notice = "群名已更新"
                        }
                    }
                )
            }
        }
    )
}

// ------------------------------------------------------------------- 小组件

/** 一张白色卡片：圆角 + 发丝线（平涂，不做玻璃）。 */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(Radii.card))
            .background(LiuliColors.Surface),
        content = content
    )
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.6.dp)
            .padding(start = 14.dp)
            .background(LiuliColors.Separator)
    )
}

@Composable
private fun InfoRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    value: String? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) LiuliColors.Danger else LiuliColors.TextSecondary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            color = if (danger) LiuliColors.Danger else LiuliColors.TextPrimary,
            fontSize = 15.5.sp,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                value,
                color = LiuliColors.TextTertiary,
                fontSize = 13.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
        Icon(
            LiuliIcons.ChevronRight,
            contentDescription = null,
            tint = LiuliColors.TextTertiary,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = LiuliColors.TextPrimary,
                fontSize = 15.5.sp
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = LiuliColors.TextTertiary,
                    fontSize = 12.sp
                )
            }
        }
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MemberRow(
    member: Member,
    self: Boolean,
    onClick: () -> Unit,
    onManage: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassAvatar(
            name = member.name,
            seed = member.avatarSeed,
            size = 40.dp,
            online = member.online,
            glassSheen = false
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    member.name.ifBlank { member.deviceId.take(8) },
                    color = LiuliColors.TextPrimary,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (self) {
                    Text(
                        "（我）",
                        color = LiuliColors.TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }
            // 群主/管理员/在线状态一次说清：离线要明确写「离线」，不能含糊成「成员」
            val roleText = when (member.role) {
                Role.OWNER -> "群主"
                Role.ADMIN -> "管理员"
                Role.MEMBER -> null
            }
            val stateText = if (member.online) "在线" else "离线"
            val muteText = if (member.muted) "已禁言" else null
            Text(
                listOfNotNull(roleText, muteText, stateText).joinToString(" · "),
                color = if (member.muted || !member.online) {
                    LiuliColors.Warn
                } else {
                    LiuliColors.TextTertiary
                },
                fontSize = 12.sp
            )
        }
        if (onManage != null) {
            Text(
                "管理",
                color = LiuliColors.Accent,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onManage)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Icon(
            LiuliIcons.ChevronRight,
            contentDescription = null,
            tint = LiuliColors.TextTertiary,
            modifier = Modifier.size(17.dp)
        )
    }
}

/**
 * 群聊头图：成员头像 4 列排布（**每格带头像下面的名字，点进个人资料**）+ 一个「+」邀请位。
 *
 * 用户反馈过两件事，这里一起修掉：
 *  * 「不能点击头像进入对方的详细信息」→ 每个成员格 `onOpenContact(member.deviceId)`；
 *  * 「下面也没有对方的名字」→ 每格头像下面显示名字（超长省略）。
 *
 * 自己的那一格不响应点击：本工程没有「我的资料」页，点了会打开一个空资料页，
 * 那属于假装有功能。
 */
@Composable
private fun GroupHeader(
    members: List<Member>,
    myDeviceId: String,
    ownerCanInvite: Boolean,
    onOpenContact: (String) -> Unit,
    onInvite: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(Radii.card))
            .background(LiuliColors.Surface)
            .padding(14.dp)
    ) {
        Text(
            "${members.size} 位成员",
            color = LiuliColors.TextTertiary,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        // 每行 4 格，最后一格是「+」邀请位
        val rows = ((members.size + 1 + 3) / 4).coerceAtLeast(1)
        var index = 0
        repeat(rows) { rowIndex ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(4) {
                    when {
                        index < members.size -> {
                            val member = members[index]
                            GroupMemberCell(
                                member = member,
                                self = member.deviceId == myDeviceId,
                                onClick = { onOpenContact(member.deviceId) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        index == members.size && ownerCanInvite -> {
                            InviteCell(onClick = onInvite, modifier = Modifier.weight(1f))
                        }

                        else -> Spacer(Modifier.weight(1f))
                    }
                    index++
                }
            }
            if (rowIndex < rows - 1) Spacer(Modifier.height(12.dp))
        }
    }
}

/** 一个成员格：头像 + 名字（+ 离线淡显）。 */
@Composable
private fun GroupMemberCell(
    member: Member,
    self: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nameColor = if (member.online || self) LiuliColors.TextSecondary else LiuliColors.TextTertiary
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !self, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            GlassAvatar(
                name = member.name,
                seed = member.avatarSeed,
                size = 46.dp,
                showInitial = false,
                glassSheen = false
            )
            if (member.role == Role.OWNER) {
                Text(
                    "主",
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiuliColors.Accent)
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
            } else if (member.role == Role.ADMIN) {
                Text(
                    "管",
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(4.dp))
                        .background(LiuliColors.Amber)
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            if (self) "我" else member.name.ifBlank { member.deviceId.take(6) },
            color = nameColor,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

/** 「+」邀请格：点了立刻打开成员选择器（不是摆设）。 */
@Composable
private fun InviteCell(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LiuliColors.SurfaceSunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                LiuliIcons.Add,
                contentDescription = "邀请成员",
                tint = LiuliColors.TextTertiary,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            "邀请",
            color = LiuliColors.TextTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}
