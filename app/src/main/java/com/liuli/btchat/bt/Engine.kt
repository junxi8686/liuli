package com.liuli.btchat.bt

import android.content.Context
import com.liuli.btchat.LiuliApp
import com.liuli.btchat.bt.call.CallController
import com.liuli.btchat.bt.call.CallMedia
import com.liuli.btchat.bt.call.CallState
import com.liuli.btchat.core.ChatEngine
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.HelloPacket
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.LinkState
import com.liuli.btchat.core.LinkStatus
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.Peer
import com.liuli.btchat.core.PeerSource
import com.liuli.btchat.core.Svc
import com.liuli.btchat.core.TransferProgress
import com.liuli.btchat.core.Wire
import com.liuli.btchat.core.newId
import com.liuli.btchat.core.seedOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * [ChatEngine] 的唯一实现：把蓝牙适配器 / [BtTransport] / [Router] / [TransferManager]
 * 串成 UI 能用的门面。
 *
 * 分层：
 * ```
 *   UI ── ChatEngine ─► Engine ─┬─ BtDiscovery   适配器状态 / 已配对 / 扫描
 *                               ├─ BtTransport   两条 RFCOMM 监听 + 客户端建链
 *                               ├─ Connection    单链路读写 / 握手 / 心跳
 *                               ├─ Router        收包分派 + 群组中继 + 会话/群管理
 *                               └─ TransferManager 文件收发
 * ```
 *
 * [Router] 与 [TransferManager] 用进程级 [coreScope] 构造，**不依赖** [start]，
 * 所以所有 [ChatEngine] 方法在 `start()` 之前调用都是安全的（没有链路时文本会落库为
 * FAILED、媒体返回 null，可稍后 resend）。只有蓝牙相关的部分才需要 [start]。
 */
object Engine : ChatEngine {

    private val linkState = MutableStateFlow(LinkStatus())
    private val peerState = MutableStateFlow<List<Peer>>(emptyList())
    private val transferState = MutableStateFlow<List<TransferProgress>>(emptyList())
    private val typingState = MutableStateFlow<Set<String>>(emptySet())
    private val callState = MutableStateFlow(CallState())

    override val link: StateFlow<LinkStatus> = linkState
    override val peers: StateFlow<List<Peer>> = peerState
    override val transfers: StateFlow<List<TransferProgress>> = transferState
    override val typing: StateFlow<Set<String>> = typingState

    /** 当前通话状态（IDLE/OUTGOING/RINGING/ACTIVE/ENDED + 静音/摄像头/参与者）。 */
    val call: StateFlow<CallState> = callState

    /** 通话媒体层：由 media-pipeline 的实现在这里装配；不装配就是「信令可用但没声音」。 */
    @Volatile
    private var pendingCallMedia: CallMedia? = null

    /** Router / TransferManager / CallController 的生命周期作用域（进程级，不随 stop() 销毁）。 */
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val core: Triple<Router, TransferManager, CallController> by lazy {
        val r = Router(Svc.store, Svc.settings, Svc.media, coreScope, typingState)
        val t = TransferManager(Svc.store, Svc.settings, Svc.media, r, coreScope, transferState)
        val c = CallController(Svc.store, Svc.settings, r, coreScope, callState)
        // 离线消息中转（存储转发）：A 要给 D 发消息但没连上 D 时，交给已连的对端保管接力。
        val relay = com.liuli.btchat.bt.relay.RelayService(r, Svc.settings, coreScope)
        c.media = pendingCallMedia
        r.blockList = pendingBlockList
        r.transfers = t
        r.relay = relay
        // 通话中自动下载的硬闸：通话已经占满链路，再下大文件会把两者一起毁掉
        t.inCall = { callState.value.busy }
        // Forwarding media re-enters through the transfer pipeline.
        r.mediaSender = { convId, imported -> sendMedia(convId, imported) }
        // Reminders are raised through the service locator, so `bt` stays free
        // of any dependency on audio or notifications.
        r.onIncomingNotice = { convId, sender, preview ->
            Svc.notifier?.invoke(convId, sender, preview)
        }
        r.onLinksChanged = {
            rebuildPeers()
            refreshLink()
        }
        r.onNotice = { msg -> logNotice(msg) }
        Triple(r, t, c)
    }

    private val router: Router get() = core.first
    private val transferMgr: TransferManager get() = core.second
    private val calls: CallController get() = core.third

    private val lock = Any()
    private val attached = ConcurrentHashMap<String, Connection>()

    @Volatile
    private var started = false

    @Volatile
    private var runScope: CoroutineScope? = null

    @Volatile
    private var transport: BtTransport? = null

    @Volatile
    private var discovery: BtDiscovery? = null

    @Volatile
    private var errorMessage: String? = null

    @Volatile
    private var notice: String = ""

    /** 最近 50 条链路事件，调试页与自测报告都会读它。 */
    private val noticeLog = ArrayDeque<String>(64)

    // ---------------------------------------------------------------- 生命周期

    override fun start() {
        synchronized(lock) {
            if (started) return
            val ctx = appCtx()
            if (ctx == null || !Svc.installed) {
                publish(LinkState.ERROR, "应用尚未初始化")
                return
            }
            Svc.settings.ensureIdentity()

            val d = discovery ?: BtDiscovery(ctx).also { discovery = it }
            d.start()

            if (!BtPermissions.canConnect(ctx)) {
                publish(LinkState.UNAUTHORIZED, "缺少蓝牙权限，请先授权")
                refreshLink()
                return
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val t = BtTransport(ctx, scope)
            t.onAccepted = { link -> attach(link, scope, incoming = true) }
            t.onListeningChanged = { logNotice(if (it) "RFCOMM 通道已监听" else "RFCOMM 通道已停止") }

            val listening = t.start()
            val r = router // 触发 core 初始化，顺带装好 transfers

            runScope = scope
            transport = t
            started = true
            errorMessage = null

            // 跟随适配器 / 扫描 / 发现结果变化刷新对 UI 暴露的状态
            scope.launch { d.discovered.collect { rebuildPeers() } }
            scope.launch { d.scanning.collect { refreshLink() } }
            scope.launch { d.adapterState.collect { st ->
                if (st != LinkState.READY) logNotice("蓝牙状态: $st")
                refreshLink()
            } }

            // 本机改名 / 换状态 / 换头像后立刻推给在线对端，
            // 否则对方要等到下次重新握手才会看到新名字。
            scope.launch {
                var last: String? = null
                Svc.settings.flow.collect { p ->
                    val sig = "${p.myName}\u0000${p.myStatus}\u0000${p.myAvatarSeed}"
                    if (last != null && last != sig) r.broadcastProfile()
                    last = sig
                }
            }

            logNotice(if (listening) "蓝牙服务已启动" else "监听通道启动失败，仅支持主动连接")
            refreshBonded()
            refreshLink()
            startAutoConnect(scope, d)
            BluetoothChatService.start(ctx)
        }
    }

    /**
     * 自动连接已配对的**好友**设备：打开 App 立刻试一次，之后每 15 秒（息屏 30 秒）一轮。
     *
     * 只连「已配对 且 在联系人表里」的设备 —— 不去连马路上任何一个配过对的设备；
     * 通话中 / 正在传文件时跳过；每个地址单独退避（见 [AutoConnector]）。
     */
    private fun startAutoConnect(scope: CoroutineScope, d: BtDiscovery) {
        val existing = autoConnector
        if (existing != null) {
            existing.start()
            return
        }
        val connector = AutoConnector(
            scope = scope,
            peers = { bondedFriendPeers() },
            isConnected = { id -> id.isNotBlank() && isConnected(id) },
            connect = { peer -> connect(peer) },
            busy = {
                callState.value.busy ||
                    transferState.value.any { it.state == com.liuli.btchat.core.TransferState.TRANSFERRING }
            },
            intervalMs = {
                if (screenInteractive()) BtConstants.AUTO_CONNECT_INTERVAL_MS
                else BtConstants.AUTO_CONNECT_IDLE_INTERVAL_MS
            }
        )
        autoConnector = connector
        // 扫描里出现的已配对设备 = 对方就在附近，立刻连，不用等下一轮
        d.onPeerFound = { peer ->
            if (peer.address.isNotBlank() && bondedFriendPeers().any { it.address == peer.address }) {
                connector.kick()
            }
        }
        connector.start()
    }

    /**
     * 已配对的设备 —— 全部都算好友。
     *
     * The user's rule, verbatim: 「只要配对上，就是直接相当于是加了好友」.
     *
     * This started as "bonded **and** in the contact list", which deadlocked the
     * whole app: the friend gate turns a message from a non-friend into a friend
     * *request*, and a request can only be delivered once a link exists — so
     * requiring friendship before connecting meant a phone with no contacts
     * could never connect, never exchange a request, and never become friends.
     * Nothing worked and nothing said why.
     *
     * Pairing is the consent step now. It happens in the system's Bluetooth
     * settings, both people take part in it, and it survives reboots — a better
     * trust signal than a flag this app invented.
     */
    private fun bondedFriendPeers(): List<Peer> {
        if (!Svc.installed) return emptyList()
        val contacts = runCatching { Svc.store.contacts() }.getOrDefault(emptyList())
        val byAddress = contacts.filter { it.address.isNotBlank() }.associateBy { it.address }
        return bondedPeers.filter { it.address.isNotBlank() }.map { p ->
            p.copy(deviceId = byAddress[p.address]?.deviceId.orEmpty())
        }
    }

    /**
     * Writes a contact row for every paired device, so 「配对即好友」 is true the
     * moment the app starts rather than only once a message happens to arrive.
     *
     * Existing rows are left alone apart from their address, so a remark the
     * user set is never overwritten.
     */
    private fun adoptBondedPeers() {
        if (!Svc.installed) return
        runCatching {
            val existing = Svc.store.contacts()
            bondedPeers.filter { it.address.isNotBlank() }.forEach { p ->
                val known = existing.firstOrNull { it.address == p.address }
                val now = System.currentTimeMillis()
                Svc.store.saveContact(
                    com.liuli.btchat.core.Contact(
                        deviceId = known?.deviceId?.takeIf { it.isNotBlank() } ?: p.address,
                        name = known?.name?.takeIf { it.isNotBlank() } ?: p.name,
                        remark = known?.remark.orEmpty(),
                        address = p.address,
                        avatarSeed = known?.avatarSeed ?: p.name.hashCode(),
                        addedAt = known?.addedAt ?: now,
                        lastSeen = known?.lastSeen ?: 0L
                    )
                )
            }
        }
    }

    private fun screenInteractive(): Boolean = runCatching {
        appCtx()?.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
    }.getOrDefault(true)

    /** 扫描诊断：为什么搜不到设备，如实给 UI。 */
    fun scanDiagnostics(): String = discovery?.diagnostics() ?: "扫描未初始化（引擎尚未 start()）"

    /** 离线中转诊断：缓存条数/字节、命中与丢弃计数。 */
    fun relayDiagnostics(): String =
        if (Svc.installed) runCatching { router.relay.statsLine() }.getOrDefault("离线中转未初始化") else "未初始化"

    /** 自动连接诊断：每个候选地址的冷却与失败次数。 */
    fun autoConnectDiagnostics(): List<String> = autoConnector?.snapshot().orEmpty()

    /** 自动连接器（诊断用）。 */
    @Volatile
    var autoConnector: AutoConnector? = null
        private set

    override fun stop() {
        synchronized(lock) {
            if (!started) {
                transferState.value = emptyList()
                typingState.value = emptySet()
                errorMessage = null
                refreshLink()
                return
            }
            started = false
            // 停引擎前先把通话收掉，别让对端一直响铃
            if (Svc.installed) {
                runCatching { if (callState.value.busy) calls.hangUp("engine-stop") }
            }
            runCatching { autoConnector?.stop() }
            runCatching { transport?.stop() }
            runCatching { router.closeAll("引擎已停止") }
            runCatching { discovery?.stop() }
            runScope?.cancel()
            runScope = null
            transport = null
            attached.clear()
            transferState.value = emptyList()
            typingState.value = emptySet()
            errorMessage = null
            logNotice("蓝牙服务已停止")
            appCtx()?.let { BluetoothChatService.stop(it) }
            rebuildPeers()
            refreshLink()
        }
    }

    /** 引擎是否已启动。 */
    val isRunning: Boolean get() = started

    /** Svc 未安装时（极早期调用）返回的空好友申请流。 */
    private val IDLE_FRIEND_REQUESTS = MutableStateFlow<List<FriendRequest>>(emptyList())

    /** 最近的链路事件（倒序，最新在前）。 */
    fun notices(): List<String> = synchronized(noticeLog) { noticeLog.toList().asReversed() }

    // ------------------------------------------------------------------ 扫描

    override fun startScan() {
        val ctx = appCtx() ?: return
        val d = discovery ?: BtDiscovery(ctx).also { discovery = it }
        // stop() 注销过 receiver，这里必须重新注册，否则 startDiscovery() 成功却
        // 没有任何人接收 ACTION_FOUND（真机上表现为「点了停止连接后再也搜不到」）
        d.start()
        if (!BtPermissions.canScan(ctx)) {
            publish(LinkState.UNAUTHORIZED, "缺少蓝牙扫描权限")
            return
        }
        if (!d.isEnabled()) {
            publish(LinkState.OFF, "蓝牙未开启")
            return
        }
        if (!d.startScan()) {
            refreshLink()
            return
        }
        refreshLink()
    }

    override fun stopScan() {
        discovery?.stopScan()
        refreshLink()
    }

    override fun refreshBonded() {
        val ctx = appCtx() ?: run {
            rebuildPeers()
            return
        }
        val d = discovery ?: BtDiscovery(ctx).also { discovery = it }
        bondedPeers = d.bondedPeers()
        // 配对即好友：每次刷新配对列表都把新配对的设备写进联系人表，
        // 否则「已配对但没聊过」的设备不会出现在通讯录里，也发不出消息。
        adoptBondedPeers()
        rebuildPeers()
    }

    // ------------------------------------------------------------------ 连接

    override fun connect(peer: Peer): Boolean {
        if (!started) start()
        val ctx = appCtx() ?: return false
        val t = transport ?: return false
        val scope = runScope ?: return false
        if (!BtPermissions.canConnect(ctx)) {
            publish(LinkState.UNAUTHORIZED, "缺少蓝牙权限")
            return false
        }
        val address = peer.address.ifBlank {
            Svc.store.contact(peer.key)?.address.orEmpty()
        }
        if (address.isBlank()) return false
        if (peer.deviceId.isNotBlank() && router.isOnline(peer.deviceId)) return true
        if (router.connectionForAddress(address) != null) return true

        publish(LinkState.CONNECTING, "正在连接 ${peer.name.ifBlank { address }}…")
        scope.launch {
            runCatching { t.open(address) }
                .onSuccess { link ->
                    logNotice("已连接 ${link.address}（${if (link.secure) "安全" else "不安全"}通道）")
                    attach(link, scope, incoming = false)
                }
                .onFailure { e ->
                    // 引擎已经停了就别再写回一条「连接失败」，那会覆盖掉停机后的正常状态
                    if (!started) return@onFailure
                    errorMessage = "连接失败: ${e.message ?: "未知错误"}"
                    logNotice(errorMessage!!)
                    refreshLink()
                }
        }
        return true
    }

    override fun disconnect(address: String) {
        if (!Svc.installed) return
        val conn = attached.values.firstOrNull { it.address == address && !it.isClosed }
        if (conn != null) {
            conn.close("用户断开")
            return
        }
        // 也接受用 deviceId 调用
        router.connectionFor(address)?.close("用户断开")
    }

    override fun isConnected(deviceId: String): Boolean {
        if (attached.values.any { it.remoteId == deviceId && it.isReady && !it.isClosed }) return true
        return Svc.installed && router.isOnline(deviceId)
    }

    /** 当前已就绪的链路数。 */
    val connectionCount: Int get() = attached.values.count { it.isReady && !it.isClosed }

    // ------------------------------------------------------------------ 消息

    override fun sendText(convId: String, text: String, quoteMsgId: String?): Message? {
        if (!Svc.installed) return null
        return router.sendText(convId, text, quoteMsgId)
    }

    override fun sendMedia(convId: String, imported: ImportedMedia): Message? {
        if (!Svc.installed) return null
        val kind = when {
            imported.mime.startsWith("image/") -> MsgKind.IMAGE
            imported.mime.startsWith("video/") -> MsgKind.VIDEO
            // 对讲机 rides the same audio pipeline but must arrive as its own
            // kind: the receiver auto-plays it and badges it 「对讲」, and
            // inferring that from a file name would be guesswork.
            imported.mime.startsWith("audio/") ->
                if (imported.ptt) MsgKind.VOICE_PTT else MsgKind.VOICE
            else -> MsgKind.FILE
        }
        transferMgr.send(convId, imported, kind)?.let { return it }
        // 发送失败（例如没有链路）：仍然落库成一条 FAILED 消息，用户可重发
        val conv = Svc.store.conversation(convId) ?: return null
        val me = Svc.settings.ensureIdentity()
        val file = File(imported.filePath)
        if (!file.exists()) return null
        val att = imported.toAttachment(newId(), kind)
        val msg = Message(
            id = newId(),
            convId = conv.id,
            senderId = me.myDeviceId,
            senderName = me.myName,
            kind = kind,
            attachment = att,
            sentAt = System.currentTimeMillis(),
            state = MsgState.FAILED,
            outgoing = true
        )
        Svc.store.saveMessage(msg)
        Svc.store.setMessageProgress(msg.id, 0L, 0f, com.liuli.btchat.core.TransferState.FAILED)
        return msg
    }

    override fun resend(messageId: String) {
        if (!Svc.installed) return
        router.resend(messageId)
    }

    override fun cancelTransfer(transferId: String) {
        if (!Svc.installed) return
        transferMgr.cancel(transferId)
    }

    override fun recall(messageId: String) {
        if (!Svc.installed) return
        router.recall(messageId)
    }

    override fun deleteForEveryone(messageId: String) {
        if (!Svc.installed) return
        router.deleteForEveryone(messageId)
    }

    override fun forward(messageId: String, toConvId: String): Message? {
        if (!Svc.installed) return null
        return router.forward(messageId, toConvId)
    }

    override fun setStarred(messageId: String, starred: Boolean) {
        if (!Svc.installed) return
        router.setStarred(messageId, starred)
    }

    override fun setTyping(convId: String, on: Boolean) {
        if (!Svc.installed) return
        router.setTyping(convId, on)
    }

    override fun markRead(convId: String) {
        if (!Svc.installed) return
        router.markRead(convId)
    }

    /** 接受一个待接收的传输（UI 弹窗用）。 */
    fun acceptTransfer(transferId: String) {
        if (!Svc.installed) return
        transferMgr.accept(transferId)
    }

    /** 拒绝一个待接收的传输。 */
    fun rejectTransfer(transferId: String) {
        if (!Svc.installed) return
        transferMgr.reject(transferId)
    }

    // ------------------------------------------------------------------ 通话

    /**
     * 装配通话媒体层（音频/摄像头实现）。
     *
     * 任何时候调用都安全：`start()` 之前赋值会缓存下来，等 [CallController] 建好再注入。
     * 不赋值时通话照常可建立，只是 `CallState.mediaReady = false`、`mediaError` 会说明原因。
     * 注意这里刻意不 import `com.liuli.btchat.media.call.*`，避免 bt/ 依赖 media/ 的实现类。
     */
    var callMedia: CallMedia?
        get() = if (Svc.installed) calls.media else pendingCallMedia
        set(value) {
            pendingCallMedia = value
            if (Svc.installed) {
                calls.media = value
                calls.refreshMediaFlags()
            }
        }

    /** 发起语音（video=false）或视频（video=true）通话。返回 false 表示没有可用链路。 */
    fun startCall(convId: String, video: Boolean = false): Boolean {
        if (!Svc.installed) return false
        return calls.startCall(convId, video)
    }

    /** 接听当前来电。 */
    fun accept() {
        if (!Svc.installed) return
        calls.accept()
    }

    /** 拒接当前来电。 */
    fun reject(reason: String = "declined") {
        if (!Svc.installed) return
        calls.reject(reason)
    }

    /** 挂断 / 取消呼叫。 */
    fun hangUp(reason: String = "hangup") {
        if (!Svc.installed) return
        calls.hangUp(reason)
    }

    /** 本地麦克风静音（会同步给对端显示）。 */
    fun setMuted(on: Boolean) {
        if (!Svc.installed) return
        calls.setMuted(on)
    }

    /** 开关摄像头（语音通话里无效）。 */
    fun setCamera(on: Boolean) {
        if (!Svc.installed) return
        calls.setCamera(on)
    }

    /** 前后摄像头翻转。 */
    fun switchCamera() {
        if (!Svc.installed) return
        calls.switchCamera()
    }

    /** 关闭已结束的通话界面状态。 */
    fun resetCall() {
        if (!Svc.installed) return
        calls.reset()
    }

    // ------------------------------------------------------------ 拉黑 / 好友

    /**
     * 拉黑名单供应者。**Di 里接一行即可**（`internal` 是模块级可见性，ui 里的
     * `blockedContacts(ctx)` 能直接调）：
     * ```kotlin
     * Engine.blockList = { blockedContacts(ctx.applicationContext).toSet() }
     * ```
     */
    var blockList: () -> Set<String>
        get() = if (Svc.installed) router.blockList else pendingBlockList
        set(value) {
            pendingBlockList = value
            if (Svc.installed) router.blockList = value
        }

    @Volatile
    private var pendingBlockList: () -> Set<String> = { emptySet() }

    /** 待处理的好友申请（UI 用来弹「XXX 请求加你为好友」）。 */
    val friendRequests: StateFlow<List<FriendRequest>>
        get() = if (Svc.installed) router.friendRequests else IDLE_FRIEND_REQUESTS

    /** 这是好友吗（= 联系人表里有这个人）。 */
    fun isFriend(deviceId: String): Boolean = Svc.installed && router.isFriend(deviceId)

    /** 同意好友申请：写入本机联系人，并回执让对方也写入。 */
    fun acceptFriend(deviceId: String) {
        if (Svc.installed) router.acceptFriend(deviceId)
    }

    /** 拒绝好友申请。 */
    fun rejectFriend(deviceId: String) {
        if (Svc.installed) router.rejectFriend(deviceId)
    }

    /** 主动发起好友申请（附带验证消息）。 */
    fun requestFriendship(deviceId: String, hello: String = ""): Boolean =
        Svc.installed && router.requestFriendship(deviceId, hello)

    /** 本机是否已把对方拉黑。 */
    fun isBlocked(deviceId: String): Boolean = Svc.installed && router.isBlocked(deviceId)

    // -------------------------------------------------------------------- 群

    override fun createGroup(name: String, avatarSeed: Int, members: List<Contact>): Conversation? {
        if (!Svc.installed || members.isEmpty()) return null
        return router.createGroup(name, avatarSeed, members)
    }

    override fun renameGroup(groupId: String, name: String) {
        if (!Svc.installed) return
        router.renameGroup(groupId, name)
    }

    override fun addGroupMember(groupId: String, contact: Contact) {
        if (!Svc.installed) return
        router.addGroupMember(groupId, contact)
    }

    override fun removeGroupMember(groupId: String, deviceId: String) {
        if (!Svc.installed) return
        router.removeGroupMember(groupId, deviceId)
    }

    override fun leaveGroup(groupId: String) {
        if (!Svc.installed) return
        router.leaveGroup(groupId)
    }

    override fun dissolveGroup(groupId: String) {
        if (!Svc.installed) return
        router.dissolveGroup(groupId)
    }

    override fun directConversationWith(contact: Contact): Conversation {
        if (!Svc.installed) {
            return Conversation(id = newId(), kind = ConvKind.DIRECT, title = contact.display, peerId = contact.deviceId)
        }
        return router.directConversationWith(contact)
    }

    override fun ensureDirectConversation(peer: Peer): Conversation {
        if (!Svc.installed) {
            return Conversation(id = newId(), kind = ConvKind.DIRECT, title = peer.name, peerId = peer.key)
        }
        return router.ensureDirectConversation(peer)
    }

    // ------------------------------------------------------------- internals

    private fun appCtx(): Context? = runCatching { LiuliApp.instance }.getOrNull()

    private fun attach(link: OpenedLink, scope: CoroutineScope, incoming: Boolean) {
        val r = core.first
        // 同一 MAC 已有链路：先关掉旧的，避免 UI 出现幽灵连接
        if (link.address.isNotBlank()) {
            attached.values.filter { it.address == link.address && !it.isClosed }.forEach { it.close("重复连接") }
        }
        val conn = Connection(
            duplex = link.duplex,
            helloFactory = { helloPacket() },
            sink = r,
            scope = scope,
            address = link.address,
            secure = link.secure,
            incoming = incoming
        )
        attached[conn.id] = conn
        r.register(conn)
        conn.start()
        refreshLink()
    }

    private fun helloPacket(): HelloPacket {
        val me = Svc.settings.ensureIdentity()
        return HelloPacket(
            deviceId = me.myDeviceId,
            name = me.myName,
            avatarSeed = me.myAvatarSeed,
            proto = Wire.PROTO_VERSION,
            appVersion = BtConstants.APP_VERSION,
            status = me.myStatus
        )
    }

    private var bondedPeers: List<Peer> = emptyList()

    /** 已配对 + 扫描到 + 已连接 + 曾经连过的设备，合并成一份列表。 */
    private fun rebuildPeers() {
        attached.entries.removeAll { it.value.isClosed }
        val merged = LinkedHashMap<String, Peer>()

        // 1) 已连接（最权威，带上了真实 deviceId）
        for (l in linkSnapshotSafe()) {
            val key = l.address.ifBlank { l.deviceId }
            if (key.isBlank()) continue
            merged[key] = Peer(
                address = l.address,
                deviceId = l.deviceId,
                name = l.name.ifBlank { Svc.store.contact(l.deviceId)?.display.orEmpty() }.ifBlank { l.deviceId.take(8) },
                avatarSeed = l.avatarSeed,
                source = PeerSource.CONNECTED,
                connected = true,
                lastSeen = System.currentTimeMillis()
            )
        }
        // 2) 已配对
        for (p in bondedPeers) {
            if (p.address.isBlank()) continue
            val known = merged[p.address]
            merged[p.address] = p.copy(
                deviceId = known?.deviceId ?: contactIdFor(p.address),
                connected = known?.connected ?: false,
                source = if (known != null) PeerSource.CONNECTED else PeerSource.BONDED
            )
        }
        // 3) 扫描到
        for (p in discovery?.discovered?.value.orEmpty()) {
            if (p.address.isBlank()) continue
            val known = merged[p.address]
            merged[p.address] = p.copy(
                deviceId = known?.deviceId ?: contactIdFor(p.address),
                connected = known?.connected ?: false,
                source = if (known != null) PeerSource.CONNECTED else PeerSource.DISCOVERED
            )
        }
        // 4) 曾经连过、存过 MAC 的联系人（不在附近也能主动连）
        if (Svc.installed) {
            for (c in Svc.store.contacts()) {
                val addr = c.address
                if (addr.isBlank() || merged.containsKey(addr)) continue
                merged[addr] = Peer(
                    address = addr,
                    deviceId = c.deviceId,
                    name = c.display,
                    avatarSeed = c.avatarSeed,
                    source = PeerSource.SAVED,
                    lastSeen = c.lastSeen
                )
            }
        }
        peerState.value = merged.values.sortedWith(
            compareByDescending<Peer> { it.connected }.thenByDescending { it.source == PeerSource.BONDED }
                .thenBy { it.name.lowercase() }
        )
    }

    /** 注意：`router` 是 lazy 的，未安装 Svc 时不能碰它。 */
    private fun linkSnapshotSafe(): List<LinkSnapshot> =
        if (Svc.installed) router.linkSnapshot() else emptyList()

    private fun contactIdFor(address: String): String =
        if (Svc.installed) Svc.store.contacts().firstOrNull { it.address == address }?.deviceId.orEmpty() else ""

    /** 重新计算 [link]，这是 UI 上那颗状态药丸的唯一数据源。 */
    private fun refreshLink() {
        val ctx = appCtx()
        if (ctx == null || !Svc.installed) {
            linkState.value = LinkStatus(LinkState.OFF, "应用尚未初始化")
            return
        }
        val d = discovery
        val conns = attached.values.count { it.isReady && !it.isClosed }
        val name = d?.adapterName.orEmpty()
        val err = errorMessage
        linkState.value = when {
            !BtPermissions.canConnect(ctx) -> LinkStatus(LinkState.UNAUTHORIZED, "缺少蓝牙权限", name, conns)
            d?.adapter == null -> LinkStatus(LinkState.OFF, "本机不支持蓝牙", "", conns)
            !d.isEnabled() -> LinkStatus(LinkState.OFF, "蓝牙未开启", name, conns)
            err != null && conns == 0 -> LinkStatus(LinkState.ERROR, err, name, conns)
            conns > 0 -> LinkStatus(LinkState.CONNECTED, "已连接 $conns 台设备", name, conns)
            d.scanning.value -> LinkStatus(LinkState.SCANNING, "正在搜索附近设备…", name, conns)
            else -> LinkStatus(LinkState.READY, notice.ifBlank { "蓝牙待命" }, name, conns)
        }
    }

    private fun publish(state: LinkState, message: String) {
        linkState.value = LinkStatus(
            state = state,
            message = message,
            adapterName = discovery?.adapterName.orEmpty(),
            connections = attached.values.count { it.isReady && !it.isClosed }
        )
    }

    private fun logNotice(msg: String) {
        notice = msg
        synchronized(noticeLog) {
            noticeLog.addFirst(msg)
            while (noticeLog.size > 50) noticeLog.removeLast()
        }
        if (started) refreshLink()
    }

    /** 供调试页/自测构造身份用的工具。 */
    fun seedFor(vararg parts: Any?): Int = seedOf(*parts)
}
