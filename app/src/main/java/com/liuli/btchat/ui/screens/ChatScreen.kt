@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.liuli.btchat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuli.btchat.bt.Engine
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.LinkState
import com.liuli.btchat.core.Member
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.Quote
import com.liuli.btchat.core.Role
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TransferState
import com.liuli.btchat.data.ChatPrefs
import com.liuli.btchat.media.VoicePlayer
import com.liuli.btchat.ui.components.ChatInputBar
import com.liuli.btchat.ui.components.ChatTimeDivider
import com.liuli.btchat.ui.components.ImagePreviewOverlay
import com.liuli.btchat.ui.components.MediaSheet
import com.liuli.btchat.ui.components.MessageActionSheet
import com.liuli.btchat.ui.components.MessageBubble
import com.liuli.btchat.ui.components.Walkie
import com.liuli.btchat.ui.glass.ChatBackground
import com.liuli.btchat.ui.glass.GlassActionSheet
import com.liuli.btchat.ui.glass.GlassButton
import com.liuli.btchat.ui.glass.GlassConfirmDialog
import com.liuli.btchat.ui.glass.GlassIconButton
import com.liuli.btchat.ui.glass.GlassLevel
import com.liuli.btchat.ui.glass.GlassSheet
import com.liuli.btchat.ui.glass.GlassStage
import com.liuli.btchat.ui.glass.GlassTopBar
import com.liuli.btchat.ui.glass.LiuliColors
import com.liuli.btchat.ui.glass.LiuliIcons
import com.liuli.btchat.ui.glass.Radii
import com.liuli.btchat.ui.glass.SheetAction
import com.liuli.btchat.ui.glass.glassSurface
import java.io.File
import kotlinx.coroutines.delay

/**
 * 聊天详情页 —— 单聊与群聊共用一屏，微信式浅色版。
 *
 * 版式与性能约定：
 *  * 页面底是平的浅灰 `LiuliColors.Bg`，没有壁纸、没有每帧重绘；
 *  * **消息列表里一个 `glassSurface` 都没有**（气泡/头像/时间胶囊/传输卡片全部平涂）——
 *    这是「滑起来非常卡」的主因：一屏十几条消息就是十几次 RenderEffect 模糊；
 *  * 玻璃只留在顶栏与弹层（长按菜单 / 媒体面板 / 确认框）。
 *
 * 数据流：
 * ```
 *   Svc.store.revision ──► 重读 conversation / messages / members / contact（唯一数据源）
 *   Svc.engine.typing  ──► 顶栏副标题的「对方正在输入…」
 *   发送/接收/重发/取消 ──► Svc.engine.*（ChatEngine 契约）
 *   接收/拒绝附件      ──► Engine.acceptTransfer / rejectTransfer
 *   引用/撤回/双向删除/转发/收藏 ──► engine.sendText(quote) / recall / deleteForEveryone
 *                                    / forward / setStarred
 * ```
 *
 * @param convId 会话 id（单聊是本机主键，群聊是 groupId）。
 * @param onBack 返回会话列表。
 * @param onOpenVideo 打开视频播放页（本地绝对路径, 文件名）。
 * @param onOpenContact 点对方头像 → 对方主页（deviceId）。
 * @param onOpenChatInfo 点右上角「…」→ 聊天信息二级页。
 * @param onOpenSearch 聊天信息页里的「查找聊天记录」→ 会话内搜索二级页。
 * @param onForward 长按菜单里的「转发」/ 多选转发 → 转发选择器二级页（消息 id）。
 * @param highlightMessageId 从搜索结果跳回来时要定位并短暂高亮的那条消息。
 */
@Composable
fun ChatScreen(
    convId: String,
    onBack: () -> Unit,
    onOpenVideo: (String, String) -> Unit,
    onOpenContact: (String) -> Unit = {},
    onOpenChatInfo: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onForward: (String) -> Unit = {},
    highlightMessageId: String? = null
) {
    val revision by Svc.store.revision.collectAsState()
    val conversation = remember(revision, convId) { Svc.store.conversation(convId) }

    if (conversation == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LiuliColors.Bg),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "会话不存在",
                    color = LiuliColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "它可能已经被删除，返回列表看看其它会话吧。",
                    color = LiuliColors.TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 40.dp)
                )
                Text(
                    "返回",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(LiuliColors.Accent)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 26.dp, vertical = 10.dp)
                )
            }
        }
    } else {
        ChatBody(
            conversation = conversation,
            revision = revision,
            onBack = onBack,
            onOpenVideo = onOpenVideo,
            onOpenContact = onOpenContact,
            onOpenChatInfo = onOpenChatInfo,
            onForward = onForward,
            highlightMessageId = highlightMessageId
        )
    }
}

@Composable
private fun ChatBody(
    conversation: Conversation,
    revision: Long,
    onBack: () -> Unit,
    onOpenVideo: (String, String) -> Unit,
    onOpenContact: (String) -> Unit,
    onOpenChatInfo: () -> Unit,
    onForward: (String) -> Unit,
    highlightMessageId: String?
) {
    val convId = conversation.id
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current

    val messages = remember(revision, convId) { Svc.store.messages(convId) }
    val members = remember(revision, convId) {
        if (conversation.kind == ConvKind.GROUP) Svc.store.members(convId) else emptyList()
    }
    val typing by Svc.engine.typing.collectAsState()
    val link by Svc.engine.link.collectAsState()

    val isGroup = conversation.kind == ConvKind.GROUP
    val peerTyping = typing.contains(convId)
    val online = conversation.peerId?.takeIf { it.isNotBlank() }?.let { Svc.engine.isConnected(it) } ?: false

    var draft by remember(convId) { mutableStateOf(conversation.draft) }
    var announcedTyping by remember(convId) { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf(false) }
    // 对讲机：开关持久化在 ChatPrefs（写入会 bump revision，这里跟着刷新）
    val prefsRevision by ChatPrefs.revision.collectAsState()
    val walkieOn = remember(convId, prefsRevision) { Walkie.enabled(convId) }
    val walkieAutoPlay = remember(prefsRevision) { Walkie.autoPlayVoice() }
    var menuFor by remember { mutableStateOf<Message?>(null) }
    var preview by remember { mutableStateOf<Message?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var quoteFor by remember { mutableStateOf<Message?>(null) }
    var multiSelect by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var bulkDelete by remember { mutableStateOf(false) }
    var highlightedId by remember { mutableStateOf<String?>(null) }

    // 浮层提示 2.2 秒后自己消失。
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2200)
            notice = null
        }
    }

    // 进屏 & 有新消息时清未读、回已读回执。
    //
    // Skipped while a call is on screen for this conversation. The call page is
    // a sibling drawn on top, so this composable stays alive underneath and used
    // to keep marking arriving messages as read — the user never saw them, yet
    // the other side got a 已读 receipt and the unread badge was cleared. Saying
    // "read" for a message nobody read is worse than saying nothing.
    val callBusy by Engine.call.collectAsState()
    LaunchedEffect(convId, messages.size, callBusy.busy) {
        if (callBusy.busy) return@LaunchedEffect
        runCatching { Svc.engine.markRead(convId) }
    }

    // 让输入栏跟着键盘抬起（imePadding）之外，列表底部也要留出键盘高度，
    // 否则最后几条会被输入法挡住。顶部留出「状态栏 + 52dp 顶栏」。
    val statusTop = WindowInsets.statusBars.getTop(density)
    val safeBottom = WindowInsets.safeDrawing.getBottom(density)
    val listTopPad = with(density) { statusTop.toDp() + 58.dp }
    val listBottomPad = with(density) { safeBottom.toDp() + 82.dp }

    // 「正在输入」用 1.4 秒的静默作为节流：期间不再重复发包，停手后自动收回。
    LaunchedEffect(draft, convId) {
        if (draft.isNotBlank()) {
            if (!announcedTyping) {
                Svc.engine.setTyping(convId, true)
                announcedTyping = true
            }
            delay(1400)
            Svc.store.setDraft(convId, draft)
            if (announcedTyping) {
                Svc.engine.setTyping(convId, false)
                announcedTyping = false
            }
        } else if (announcedTyping) {
            Svc.engine.setTyping(convId, false)
            announcedTyping = false
        }
    }
    DisposableEffect(convId) {
        // Tell the reminder path which conversation is on screen, so a message
        // arriving here does not ring, buzz or post a notification at the user
        // who is already reading it.
        Svc.activeConvId.value = convId
        onDispose {
            if (Svc.activeConvId.value == convId) Svc.activeConvId.value = null
            if (announcedTyping) Svc.engine.setTyping(convId, false)
            // 离开会话就把语音播放掐掉，别在别的页面里还在说话。
            runCatching { VoicePlayer.stop() }
        }
    }

    // ------------------------------------------------------------ 交互动作

    fun toast(text: String) {
        notice = text
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val quoting = quoteFor?.id
        draft = ""
        quoteFor = null
        announcedTyping = false
        Svc.engine.setTyping(convId, false)
        Svc.store.setDraft(convId, "")
        val sent = Svc.engine.sendText(convId, text, quoting)
        when {
            sent == null -> toast("发送失败，会话可能已不存在")
            sent.state == MsgState.FAILED -> toast("没有可用的蓝牙连接，消息已标记为待重发")
        }
    }

    // ------------------------------------------------------------ 多选 / 菜单动作

    fun toggleSelect(m: Message) {
        if (selectedIds.contains(m.id)) selectedIds.remove(m.id) else selectedIds.add(m.id)
    }

    fun enterMultiSelect(seed: Message?) {
        multiSelect = true
        selectedIds.clear()
        if (seed != null) selectedIds.add(seed.id)
        focus.clearFocus()
    }

    fun exitMultiSelect() {
        multiSelect = false
        selectedIds.clear()
        bulkDelete = false
    }

    /** 仅本机删除：附件文件一并收掉（导入 id 与传输 id 不同，必须按附件路径删）。 */
    fun deleteLocal(m: Message, silent: Boolean = false) {
        val att = m.attachment
        if (att != null) {
            if (att.state == TransferState.TRANSFERRING ||
                att.state == TransferState.OFFERED ||
                att.state == TransferState.WAIT_ACCEPT
            ) {
                runCatching { Svc.engine.cancelTransfer(att.transferId) }
            }
            runCatching { Svc.media.deleteAttachmentFiles(att) }
        }
        Svc.store.deleteMessage(m.id)
        if (!silent) toast("已删除")
    }

    fun recall(m: Message) {
        runCatching { Svc.engine.recall(m.id) }
        toast("已撤回")
    }

    fun toggleStar(m: Message) {
        val on = !m.starred
        runCatching { Svc.engine.setStarred(m.id, on) }
        toast(if (on) "已收藏" else "已取消收藏")
    }

    fun bulkDeleteLocal() {
        selectedIds.toList().forEach { id -> Svc.store.message(id)?.let { deleteLocal(it, silent = true) } }
        toast("已删除 ${selectedIds.size} 条")
        exitMultiSelect()
    }

    fun bulkDeleteBoth() {
        val ids = selectedIds.toList()
        // Only my own messages can be deleted for both sides.
        //
        // The single-message menu guards on `m.outgoing`; this bulk path did not,
        // so selecting a message the *other* person sent and choosing 「删除（双方）」
        // silently deleted it on their phone too — and took their file with it,
        // because the receiving side deletes the attachment. Deleting someone
        // else's message out of their own history is not a thing a chat app
        // should be able to do.
        val mine = ids.filter { Svc.store.message(it)?.outgoing == true }
        val skipped = ids.size - mine.size
        mine.forEach { id -> runCatching { Svc.engine.deleteForEveryone(id) } }
        toast(
            when {
                mine.isEmpty() -> "只能为双方删除自己发出的消息"
                skipped > 0 -> "已为双方删除 ${mine.size} 条（${skipped} 条不是自己发的，已跳过）"
                else -> "已为双方删除 ${mine.size} 条"
            }
        )
        exitMultiSelect()
    }

    fun bulkStar() {
        val ids = selectedIds.toList()
        ids.forEach { id -> runCatching { Svc.engine.setStarred(id, true) } }
        toast("已收藏 ${ids.size} 条")
        exitMultiSelect()
    }

    fun bulkForward() {
        val first = selectedIds.firstOrNull()
        if (first == null) {
            toast("先选中消息")
            return
        }
        exitMultiSelect()
        onForward(first)
    }

    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("琉璃", text))
        toast("已复制")
    }

    fun localFile(m: Message): File? =
        m.attachment?.localPath?.let { File(it) }?.takeIf { it.isFile && it.length() > 0L }

    fun saveToGallery(m: Message) {
        val att = m.attachment ?: return
        val file = localFile(m) ?: run {
            toast("文件还在传输中")
            return
        }
        toast(if (Svc.media.exportToGallery(file, att.mime)) "已保存到相册" else "保存失败")
    }

    fun openAttachment(m: Message) {
        val att = m.attachment ?: return
        val file = localFile(m)
        if (file == null) {
            toast("文件还在传输中…")
            return
        }
        when (m.kind) {
            MsgKind.VIDEO -> onOpenVideo(file.absolutePath, att.fileName)
            MsgKind.IMAGE -> preview = m
            else -> runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Svc.media.shareUri(file), att.mime.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }.onFailure { toast("没有可以打开这个文件的应用") }
        }
    }

    // -------------------------------------------------------------- 列表数据

    val rows = remember(messages) {
        val out = ArrayList<ChatRow>(messages.size)
        var prev: Message? = null
        for (m in messages) {
            val p = prev
            val gap = p == null || m.sentAt - p.sentAt > TIME_GAP_MS
            out.add(
                ChatRow(
                    message = m,
                    showTime = gap,
                    showSender = p == null || p.senderId != m.senderId || gap
                )
            )
            prev = m
        }
        out
    }

    val listState = rememberLazyListState()
    var firstFill by remember(convId) { mutableStateOf(true) }
    LaunchedEffect(rows.size, convId) {
        if (rows.isNotEmpty()) {
            // 带一个很大的 scrollOffset，让列表一定贴到「真正的最底部」（含底部留白），
            // 只给 index 的话最后一条会被输入栏压住半截。
            val bottom = rows.lastIndex
            if (firstFill) {
                listState.scrollToItem(bottom, BOTTOM_SCROLL_OFFSET_PX)
                firstFill = false
            } else {
                listState.animateScrollToItem(bottom, BOTTOM_SCROLL_OFFSET_PX)
            }
        }
    }

    // 键盘弹起时输入栏跟着抬起，列表也要顺势滚到底，否则刚打的那条会被挡住。
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && rows.isNotEmpty()) {
            listState.animateScrollToItem(rows.lastIndex, BOTTOM_SCROLL_OFFSET_PX)
        }
    }

    // 从「查找聊天记录」跳回来：滚到那一条并高亮一小会儿。
    LaunchedEffect(highlightMessageId, rows.size) {
        val target = highlightMessageId ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.message.id == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            highlightedId = target
            delay(1800)
            highlightedId = null
        }
    }

    // 对讲机：收到新的对讲语音、且文件已经到位 → 自动播放一次
    // （「自动接收」由 bt-transport 保证；这里是「自动播放」，开关默认开、可以关）
    var lastAutoPlayed by remember(convId) { mutableStateOf<String?>(null) }
    LaunchedEffect(revision, walkieOn, walkieAutoPlay) {
        if (!walkieOn || !walkieAutoPlay) return@LaunchedEffect
        val candidate = messages.lastOrNull { m ->
            !m.outgoing && !m.recalled && Walkie.isPtt(m) &&
                m.attachment?.localPath?.let { File(it).isFile } == true
        } ?: return@LaunchedEffect
        if (candidate.id != lastAutoPlayed) {
            lastAutoPlayed = candidate.id
            val path = candidate.attachment?.localPath
            if (path != null) runCatching { VoicePlayer.toggle(path) }
        }
    }

    val subtitle = when {
        peerTyping -> "对方正在输入…"
        isGroup -> "${if (members.isNotEmpty()) members.size else conversation.memberCount} 人"
        online -> "在线"
        link.state == LinkState.CONNECTED -> "蓝牙已连接"
        else -> "未连接"
    }

    // 标题实时解析：会话标题是建会话时冻结的，老数据里可能被写成「我」，
    // 所以单聊优先取联系人备注/昵称，取不到再回退会话标题；群聊用群名。
    val contact = remember(revision, convId) {
        conversation.peerId?.takeIf { it.isNotBlank() }?.let { Svc.store.contact(it) }
    }
    val title = when {
        isGroup -> conversation.title.takeIf { it.isNotBlank() } ?: "群聊"
        else -> contact?.display?.takeIf { it.isNotBlank() && it != "我" }
            ?: conversation.title.takeIf { it.isNotBlank() }
            ?: "聊天"
    }

    GlassStage(
        background = {
            // Per-conversation wallpaper, chosen on the chat-info page.
            ChatBackground(convId = convId) { }
        },
        content = {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { focus.clearFocus() })
                    }
            ) {
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ChatEmptyHint(isGroup = isGroup)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = listTopPad, bottom = listBottomPad),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items = rows, key = { it.message.id }) { row ->
                        Column(Modifier.fillMaxWidth()) {
                            if (row.showTime) ChatTimeDivider(row.message.sentAt)
                            MessageBubble(
                                message = row.message,
                                isGroup = isGroup,
                                showSenderName = row.showSender,
                                selectionMode = multiSelect,
                                selected = selectedIds.contains(row.message.id),
                                highlighted = highlightedId == row.message.id,
                                onLongPress = { if (!multiSelect) menuFor = it },
                                onOpenImage = { openAttachment(it) },
                                onOpenVideo = { openAttachment(it) },
                                onRetry = { runCatching { Svc.engine.resend(it.id) } },
                                onAccept = { m ->
                                    m.attachment?.let { runCatching { EngineBridge.accept(it.transferId) } }
                                },
                                onReject = { m ->
                                    m.attachment?.let { runCatching { EngineBridge.reject(it.transferId) } }
                                },
                                onCancelTransfer = { m ->
                                    m.attachment?.let { runCatching { Svc.engine.cancelTransfer(it.transferId) } }
                                },
                                onToggleSelect = { toggleSelect(it) },
                                onOpenContact = onOpenContact
                            )
                        }
                    }
                }
            }
        },
        overlay = {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (multiSelect) {
                        // 多选模式：顶栏换成「已选 N 项」+ 取消
                        GlassTopBar(
                            title = "已选 ${selectedIds.size} 项",
                            centered = true,
                            onBack = { exitMultiSelect() },
                            trailing = {
                                Text(
                                    "取消",
                                    color = LiuliColors.Accent,
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .clickable { exitMultiSelect() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        )
                    } else {
                        GlassTopBar(
                            title = title,
                            subtitle = subtitle,
                            centered = true,
                            onBack = onBack,
                            trailing = {
                                // 顶栏只留「…」；通话/对讲/媒体入口都并到输入栏右下角那个「+」里
                                // （用户要求：和微信一样，右上角不要加号）。
                                GlassIconButton(
                                    onClick = {
                                        focus.clearFocus()
                                        onOpenChatInfo()
                                    },
                                    size = 40.dp,
                                    level = GlassLevel.Thin,
                                    bordered = false
                                ) {
                                    Icon(
                                        LiuliIcons.More,
                                        contentDescription = "聊天信息",
                                        tint = LiuliColors.TextPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    if (multiSelect) {
                        SelectionActionBar(
                            count = selectedIds.size,
                            onForward = { bulkForward() },
                            onStar = { bulkStar() },
                            onDelete = { bulkDelete = true }
                        )
                    } else {
                        Box(
                            // 只在这里做 imePadding；navigationBarsPadding 交给输入栏自己，
                            // 让它落在白底之内（白条要一直铺到系统导航条后面）。
                            Modifier.imePadding()
                        ) {
                            ChatInputBar(
                                value = draft,
                                onValueChange = { draft = it },
                                onSend = { send() },
                                onOpenMedia = {
                                    focus.clearFocus()
                                    mediaOpen = true
                                },
                                quote = quoteFor?.let { quote ->
                                    Quote(quote.id, quote.senderName, quote.preview)
                                },
                                onCancelQuote = { quoteFor = null },
                                onVoice = { take ->
                                    // 对讲机模式下把这条标记成 VOICE_PTT（引擎按 imported.ptt 出 kind）
                                    val imported = take.toImportedMedia().let {
                                        if (walkieOn) it.copy(ptt = true) else it
                                    }
                                    val sent = Svc.engine.sendMedia(convId, imported)
                                    toast(
                                        when {
                                            sent == null -> "语音发送失败：没有可用的蓝牙连接"
                                            walkieOn -> "对讲已发出（${(take.durationMs / 1000).coerceAtLeast(1)} 秒）"
                                            else -> "语音已发出（${(take.durationMs / 1000).coerceAtLeast(1)} 秒）"
                                        }
                                    )
                                },
                                onNotice = { toast(it) }
                            )
                        }
                    }
                }

                // 提示胶囊（微信式黑底 toast，不用玻璃）
                AnimatedVisibility(
                    visible = notice != null,
                    modifier = Modifier.align(Alignment.TopCenter),
                    enter = fadeIn(tween(150)),
                    exit = fadeOut(tween(180))
                ) {
                    Text(
                        notice.orEmpty(),
                        color = Color.White,
                        fontSize = 13.5.sp,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(top = 66.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xE6000000))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                // 媒体面板（相册 / 拍照 / 视频 / 文件 + 通话 + 对讲机，全在输入栏那个「+」里）
                MediaSheet(
                    visible = mediaOpen,
                    convId = convId,
                    isGroup = isGroup,
                    onDismiss = { mediaOpen = false },
                    onReady = { imported ->
                        val sent = Svc.engine.sendMedia(convId, imported)
                        toast(
                            if (sent == null) "发送失败：没有可用的蓝牙连接"
                            else "正在发送「${imported.name}」"
                        )
                        mediaOpen = false
                    },
                    onNotice = { toast(it) }
                )

                // 顶栏「+」已删除：通话/对讲入口并进了输入栏右下角的 MediaSheet
                // （下面的「长按消息的操作菜单」之后就是输入栏的 + 面板）。

                // 长按消息的操作菜单（电报式：复制/引用/转发/收藏/多选/撤回/删除）
                MessageActionSheet(
                    message = menuFor,
                    onDismiss = { menuFor = null },
                    onCopy = { copyText(it.text) },
                    onQuote = { m ->
                        quoteFor = m
                        multiSelect = false
                    },
                    onForward = { m -> onForward(m.id) },
                    onToggleStar = { toggleStar(it) },
                    onMultiSelect = { enterMultiSelect(it) },
                    onRecall = { recall(it) },
                    onDeleteLocal = { deleteLocal(it) },
                    onDeleteBoth = { m ->
                        runCatching { Svc.engine.deleteForEveryone(m.id) }
                        toast("已为双方删除")
                    }
                )

                // 批量删除：同样是「仅我可见 / 双方」两种语义
                GlassActionSheet(
                    visible = bulkDelete,
                    onDismiss = { bulkDelete = false },
                    title = "删除选中的 ${selectedIds.size} 条",
                    actions = listOf(
                        SheetAction(
                            icon = LiuliIcons.Delete,
                            label = "删除（仅我可见）",
                            description = "只清掉本机的记录",
                            danger = true,
                            onClick = {
                                bulkDelete = false
                                bulkDeleteLocal()
                            }
                        ),
                        SheetAction(
                            icon = LiuliIcons.Cancel,
                            label = "删除（双方）",
                            description = "会通知对方一起删掉",
                            danger = true,
                            onClick = {
                                bulkDelete = false
                                bulkDeleteBoth()
                            }
                        ),
                        SheetAction(
                            icon = LiuliIcons.Back,
                            label = "取消",
                            onClick = { bulkDelete = false }
                        )
                    )
                )

                // 全屏看图
                ImagePreviewOverlay(
                    message = preview,
                    onDismiss = { preview = null },
                    onSave = { m ->
                        saveToGallery(m)
                        preview = null
                    }
                )
            }
        }
    )
}

// ------------------------------------------------------------------ 多选操作条

/** 多选模式下的底部操作条：转发 / 收藏 / 删除。 */
@Composable
private fun SelectionActionBar(
    count: Int,
    onForward: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit
) {
    val enabled = count > 0
    Row(
        Modifier
            .fillMaxWidth()
            .background(LiuliColors.Surface)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BulkAction("转发", enabled, Modifier.weight(1f), onForward)
        BulkAction("收藏", enabled, Modifier.weight(1f), onStar)
        BulkAction("删除", enabled, Modifier.weight(1f), onDelete, danger = true)
    }
}

@Composable
private fun BulkAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Column(
        modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            when (label) {
                "转发" -> LiuliIcons.Share
                "收藏" -> LiuliIcons.Star
                else -> LiuliIcons.Delete
            },
            contentDescription = label,
            tint = when {
                !enabled -> LiuliColors.TextTertiary
                danger -> LiuliColors.Danger
                else -> LiuliColors.TextPrimary
            },
            modifier = Modifier.size(21.dp)
        )
        Text(
            label,
            color = when {
                !enabled -> LiuliColors.TextTertiary
                danger -> LiuliColors.Danger
                else -> LiuliColors.TextSecondary
            },
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

/** 空会话提示：平涂，没有任何玻璃。 */
@Composable
private fun ChatEmptyHint(isGroup: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(LiuliColors.SurfaceSunken),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isGroup) LiuliIcons.Group else LiuliIcons.ChatAlt,
                contentDescription = null,
                tint = LiuliColors.TextTertiary,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            "还没有消息",
            color = LiuliColors.TextSecondary,
            fontSize = 15.sp
        )
        Text(
            if (isGroup) "在群里说点什么吧" else "打个招呼，开始你们的蓝牙对话",
            color = LiuliColors.TextTertiary,
            fontSize = 13.sp
        )
    }
}

// ------------------------------------------------------------------ 小部件

/** 相邻消息相隔超过 5 分钟就插一条时间胶囊。 */
private const val TIME_GAP_MS = 5 * 60 * 1000L

/** 滚到底时额外多滚的像素：足够把底部留白也吃掉，clamp 之后就是真正的最底部。 */
private const val BOTTOM_SCROLL_OFFSET_PX = 20_000

/** 列表里的一行：消息 + 它上面要不要画时间/昵称。 */
private data class ChatRow(
    val message: Message,
    val showTime: Boolean,
    val showSender: Boolean
)

private fun messageTitle(m: Message): String = when (m.kind) {
    MsgKind.TEXT -> "文本消息"
    MsgKind.IMAGE -> "图片"
    MsgKind.VIDEO -> "视频"
    MsgKind.VOICE -> "语音"
    MsgKind.VOICE_PTT -> "对讲"
    MsgKind.FILE -> m.attachment?.fileName ?: "文件"
    MsgKind.SYSTEM -> "系统消息"
}

/**
 * [com.liuli.btchat.core.ChatEngine] 的冻结契约里没有"接收/拒绝附件"这两个动作
 * （只有 `cancelTransfer`），而唯一实现 [com.liuli.btchat.bt.Engine] 上有一对
 * 明确标注"UI 弹窗用"的公开方法。这里直接落到实现上，避免去改冻结的 core 契约。
 */
private object EngineBridge {
    fun accept(transferId: String) = com.liuli.btchat.bt.Engine.acceptTransfer(transferId)

    fun reject(transferId: String) = com.liuli.btchat.bt.Engine.rejectTransfer(transferId)
}

/** 带输入框的弹窗（改群名用），ChatInfoScreen 也用它。 */
@Composable
internal fun GlassTextPrompt(
    visible: Boolean,
    title: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.46f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140))
        ) {
            var text by remember { mutableStateOf(initial) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 30.dp)
                    .glassSurface(shape = RoundedCornerShape(Radii.panel), level = GlassLevel.Regular)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    title,
                    color = LiuliColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(shape = RoundedCornerShape(Radii.field), level = GlassLevel.Thin)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        if (text.isEmpty()) {
                            Text(
                                hint,
                                color = LiuliColors.TextTertiary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = LiuliColors.TextPrimary),
                            cursorBrush = SolidColor(LiuliColors.Accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        level = GlassLevel.Thin,
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "取消",
                                color = LiuliColors.TextSecondary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    GlassButton(
                        onClick = { onConfirm(text) },
                        modifier = Modifier.weight(1f),
                        level = GlassLevel.Regular,
                        tint = LiuliColors.Violet.copy(alpha = 0.38f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "保存",
                                color = LiuliColors.TextPrimary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
