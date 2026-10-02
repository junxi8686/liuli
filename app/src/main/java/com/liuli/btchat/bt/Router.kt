package com.liuli.btchat.bt

import com.liuli.btchat.bt.call.CallSignaling
import com.liuli.btchat.core.CallAcceptPacket
import com.liuli.btchat.core.CallEndPacket
import com.liuli.btchat.core.CallFrame
import com.liuli.btchat.core.CallInvitePacket
import com.liuli.btchat.core.CallRejectPacket
import com.liuli.btchat.core.CallStatePacket
import com.liuli.btchat.core.ChatStore
import com.liuli.btchat.core.Chunk
import com.liuli.btchat.core.Contact
import com.liuli.btchat.core.Conversation
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.DeleteMessagePacket
import com.liuli.btchat.core.Envelope
import com.liuli.btchat.core.FileAcceptPacket
import com.liuli.btchat.core.FileAbortPacket
import com.liuli.btchat.core.FileDonePacket
import com.liuli.btchat.core.FileOfferPacket
import com.liuli.btchat.core.FileRejectPacket
import com.liuli.btchat.core.FriendAcceptPacket
import com.liuli.btchat.core.FriendRejectPacket
import com.liuli.btchat.core.FriendRequestPacket
import com.liuli.btchat.core.GroupInvitePacket
import com.liuli.btchat.core.GroupUpdatePacket
import com.liuli.btchat.core.ImportedMedia
import com.liuli.btchat.core.MediaVault
import com.liuli.btchat.core.Member
import com.liuli.btchat.core.MemberDto
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.Packet
import com.liuli.btchat.core.Peer
import com.liuli.btchat.core.PingPacket
import com.liuli.btchat.core.PongPacket
import com.liuli.btchat.core.ProfilePacket
import com.liuli.btchat.core.Quote
import com.liuli.btchat.core.QuoteDto
import com.liuli.btchat.core.ReceiptPacket
import com.liuli.btchat.core.RecallPacket
import com.liuli.btchat.core.RelayAckPacket
import com.liuli.btchat.core.RelayEnvelopePacket
import com.liuli.btchat.core.Role
import com.liuli.btchat.core.SettingsApi
import com.liuli.btchat.core.TextPacket
import com.liuli.btchat.core.TypingPacket
import com.liuli.btchat.core.Wire
import com.liuli.btchat.core.newId
import com.liuli.btchat.core.seedOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 一条已就绪链路的快照，供 UI 展示「已连接」设备。 */
data class LinkSnapshot(
    val deviceId: String,
    val name: String,
    val avatarSeed: Int,
    val address: String
)

/**
 * 消息路由器：把 [Connection] 收到的帧翻译成对 [ChatStore] 的写操作，并把上层
 * 要发的东西选好链路发出去。
 *
 * 三件核心工作：
 *
 * 1. **分派**：TEXT → 落库 + 回回执；RECEIPT → 更新自己发出去的消息状态；
 *    TYPING → 更新 typing 集合（带 6 秒 TTL）；GROUP_INVITE/GROUP_UPDATE → 建群/改群；
 *    FILE_* → 交给 [TransferManager]。
 * 2. **路由**：星型拓扑下成员之间没有直连，所有包都经群主中转。[sendTo] 优先走直连，
 *    没有直连就交给群主代转（`to = 目标`），群主再把 hop 加一转发。
 * 3. **群组中继**：本机是群主、且收到 `to == null && groupId != null` 的广播包时，
 *    转发给该群所有其他在线成员（`hop + 1`）；`hop > 1` 视为环路直接丢弃。
 *    群主的 FILE_* 也要中转，见 [onChunk] 与 [relayRoutes]。
 *
 * 所有依赖都从构造函数注入（而不是直接摸 `Svc`），因此 [SelfTest] 能在同一进程里
 * 用 loopback 起两三个独立「节点」，跑同一套 Router/TransferManager。
 */
class Router(
    private val store: ChatStore,
    private val settings: SettingsApi,
    private val media: MediaVault,
    private val scope: CoroutineScope,
    private val typingSink: MutableStateFlow<Set<String>>
) : Connection.Sink {

    companion object {
        /** GROUP_UPDATE 里表示「群已解散」的 revision。 */
        const val REV_DISSOLVE = -1L

        /**
         * 被拉黑时回的拒收回执状态。复用现成的 [ReceiptPacket]，core 零改动：
         * 发送方收到 `state == "BLOCKED"` 就把该消息置 FAILED 并提示「被对方拒收」。
         */
        const val RECEIPT_BLOCKED = "BLOCKED"
    }

    /** 文件传输管理器，构造完成后由 Engine/SelfTest 注入（两边互相引用）。 */
    lateinit var transfers: TransferManager

    /**
     * 离线消息中转（存储转发）。构造完成后由 Engine/SelfTest 注入。
     *
     * 不变量：中继投递最终会回到本类的 [onEnvelope]，所以拉黑拦截、msgId 去重、
     * 群聊中继、落库这些业务规则**对中继消息同样生效** —— 改 [onEnvelope] 时要知道
     * 它同时服务直连投递与中继投递两条路径。
     */
    lateinit var relay: com.liuli.btchat.bt.relay.RelayService

    /** 通话信令与媒体帧的落点，由 [com.liuli.btchat.bt.call.CallController] 注册。 */
    @Volatile
    var callSignaling: CallSignaling? = null

    /** 链路集合发生变化（连上/断开/握手完成）。 */
    @Volatile
    var onLinksChanged: (() -> Unit)? = null

    /** 需要暴露给日志/调试页的信息。 */
    @Volatile
    var onNotice: ((String) -> Unit)? = null

    /**
     * 拉黑名单供应者。由 Engine / Di 注入（UI 里的 SharedPreferences 是唯一数据源）。
     * 返回的是被本机拉黑的 deviceId 集合。
     *
     * 赋值时会立刻让缓存失效 —— 用户刚点完「拉黑」，下一条消息就必须被拦住，
     * 不能等 TTL 过期。
     */
    @Volatile
    var blockList: () -> Set<String> = { emptySet() }
        set(value) {
            field = value
            synchronized(blockLock) { blockCachedAt = 0L }
        }

    private val blockLock = Any()
    private var blockCachedAt = 0L
    private var blockCached: Set<String> = emptySet()

    private val friendRequestsState = MutableStateFlow<List<FriendRequest>>(emptyList())

    /** 待处理的好友申请（UI 用来弹「XXX 请求加你为好友」）。 */
    val friendRequests: StateFlow<List<FriendRequest>> = friendRequestsState

    /** 正在输入提示，读写都在本类内部。 */
    val typing: StateFlow<Set<String>> = typingSink

    /** 已握手完成的链路，key = 链路唯一 id。 */
    private val links = ConcurrentHashMap<String, Connection>()

    /** deviceId → 链路。 */
    private val byDevice = ConcurrentHashMap<String, Connection>()

    /** MAC → 链路（UI 用 Peer.address 断开时用）。 */
    private val byAddress = ConcurrentHashMap<String, Connection>()

    /** transferId → 群主中转路由。 */
    private val relayRoutes = ConcurrentHashMap<String, RelayRoute>()

    /** 群资料版本号，用来丢弃乱序的旧 GROUP_UPDATE。 */
    private val groupRevision = ConcurrentHashMap<String, Long>()

    /** 正在输入的会话 → 过期时间戳。 */
    private val typingUntil = ConcurrentHashMap<String, Long>()

    /** 包去重表（LRU 容量 [BtConstants.SEEN_CAPACITY]）。 */
    private val seenLock = Any()
    private val seen = LinkedHashMap<String, Long>()

    private class RelayRoute(val source: Connection, val targets: MutableList<Connection>) {
        /** 已经从这条路由上接受传输的成员 deviceId。 */
        val accepted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    init {
        // typing 过期清理
        scope.launch {
            while (isActive) {
                delay(2_000)
                publishTyping()
            }
        }
    }

    // ------------------------------------------------------------------ 身份

    fun myId(): String = settings.ensureIdentity().myDeviceId

    fun myName(): String = settings.ensureIdentity().myName

    fun mySeed(): Int = settings.ensureIdentity().myAvatarSeed

    /**
     * Set by the engine so that forwarding a photo can reuse the transfer
     * pipeline instead of the router having to know about it.
     */
    var mediaSender: ((String, ImportedMedia) -> Message?)? = null

    /**
     * Set by the engine: raise the per-chat reminder for a newly arrived
     * message. Kept as a lambda so the transport never learns about audio.
     */
    var onIncomingNotice: ((convId: String, senderName: String, preview: String) -> Unit)? = null

    /** Pushes the local profile to every live peer.
     *
     * Without this a rename only travelled on the *next* handshake, so the other
     * phone kept showing the old name for as long as the link stayed up.
     */
    fun broadcastProfile(): Int {
        val me = settings.ensureIdentity()
        val packet = ProfilePacket(me.myDeviceId, me.myName, me.myAvatarSeed, settings.current().myStatus)
        var sent = 0
        for (conn in readyLinks()) {
            if (conn.remoteId.isBlank()) continue
            if (sendPacketTo(conn.remoteId, null, packet)) sent++
        }
        return sent
    }

    // -------------------------------------------------------------- 链路管理

    /** 登记一条新链路（握手前调用）。 */
    fun register(conn: Connection) {
        links[conn.id] = conn
        if (conn.address.isNotBlank()) byAddress[conn.address] = conn
    }

    /** 当前已握手的链路。 */
    fun readyLinks(): List<Connection> = links.values.filter { it.isReady && !it.isClosed }

    /** 已握手链路数。 */
    val readyCount: Int get() = readyLinks().size

    /** 已连接设备的快照。 */
    fun linkSnapshot(): List<LinkSnapshot> = readyLinks().map {
        LinkSnapshot(it.remoteId, it.remoteName, it.remoteSeed, it.address)
    }

    fun isOnline(deviceId: String): Boolean = byDevice[deviceId]?.let { it.isReady && !it.isClosed } == true

    fun connectionFor(deviceId: String): Connection? = byDevice[deviceId]?.takeIf { it.isReady && !it.isClosed }

    fun connectionForAddress(address: String): Connection? =
        byAddress[address]?.takeIf { !it.isClosed } ?: readyLinks().firstOrNull { it.address == address }

    /** 关闭全部链路。 */
    fun closeAll(reason: String) {
        for (c in links.values.toList()) c.close(reason)
        links.clear()
        byDevice.clear()
        byAddress.clear()
        relayRoutes.clear()
        publishTyping()
        onLinksChanged?.invoke()
    }

    override fun onReady(conn: Connection) {
        val id = conn.remoteId
        if (id.isBlank()) return
        if (conn.address.isNotBlank()) byAddress[conn.address] = conn
        val previous = byDevice.put(id, conn)
        if (previous != null && previous !== conn && !previous.isClosed) {
            // 同一台设备重复连接：保留新链路
            previous.close("重复连接，已替换")
        }
        // 只刷新**已存在**的联系人，绝不因为连上就自动加好友 ——
        // 否则「删除好友」会在下次重连时被悄悄恢复（用户实测就是这个问题）。
        refreshKnownContact(conn)
        migrateDirectConv(conn.address, id)
        // 新链路 = 一个新的搬运机会：把手上替别人保管的信封全量推给它
        if (::relay.isInitialized) runCatching { relay.onLinkReady(conn) }
        // 把本机最新资料推给对方（改名/换头像后立刻可见）
        sendTo(
            id,
            Envelope(
                from = myId(),
                to = id,
                packet = ProfilePacket(myId(), myName(), mySeed(), settings.current().myStatus)
            )
        )
        onNotice?.invoke("已连接 ${conn.remoteName.ifBlank { id.take(8) }}")
        onLinksChanged?.invoke()
    }

    override fun onClosed(conn: Connection, reason: String) {
        links.remove(conn.id)
        byDevice.remove(conn.remoteId, conn)
        byAddress.remove(conn.address, conn)
        relayRoutes.entries.removeAll { route ->
            route.value.source === conn || route.value.targets.all { it.isClosed || it === conn }
        }
        if (::transfers.isInitialized) transfers.onLinkClosed(conn)
        onNotice?.invoke("链路断开（${conn.remoteName.ifBlank { conn.address }}）: $reason")
        onLinksChanged?.invoke()
    }

    override fun onChunk(conn: Connection, chunk: Chunk) {
        // 1) 群主中转：把来源链路上的分片转发给本路由的其他接受方
        val route = relayRoutes[chunk.transferId]
        if (route != null && route.source === conn) {
            var forwarded = 0
            for (t in route.targets.toList()) {
                if (t === conn || t.isClosed) continue
                if (route.accepted.isNotEmpty() && t.remoteId.isNotBlank() && t.remoteId !in route.accepted) continue
                val task = Connection.WriteTask("relay-chunk:${chunk.seq}") { out ->
                    Wire.writeChunk(out, chunk.transferId, chunk.seq, chunk.buf, chunk.offset, chunk.length)
                }
                if (t.tryEnqueue(task)) forwarded++
            }
            if (forwarded == 0 && route.targets.none { !it.isClosed }) relayRoutes.remove(chunk.transferId)
        }
        // 2) 本机自己也可能是接收方
        if (::transfers.isInitialized) transfers.onChunk(conn, chunk)
    }

    /**
     * 实时通话媒体帧：只做落点转发，不进去重表、不落库、不缓冲。
     * 实时媒体的价值在于新鲜，任何排队都是在破坏它。
     */
    override fun onCallFrame(conn: Connection, frame: CallFrame) {
        callSignaling?.onMediaFrame(conn, frame)
    }
    // ------------------------------------------------------------ 收包入口

    override fun onEnvelope(conn: Connection, env: Envelope) {
        val me = myId()
        if (env.from.isBlank() || env.from == me) return
        // 心跳包已在 Connection 层消费，这里只做兜底
        if (env.packet is PingPacket || env.packet is PongPacket) return

        // ---- 拉黑拦截：对方发来的一切「内容/请求」在这里就断掉 ----
        // 规则按用户要求：我拉黑他之后**我仍然能发给他**，他发给我的全部丢弃
        // （不入库、不提醒、不显示），并且回一个拒收回执让他知道。
        if (isBlockablePacket(env.packet) && isBlocked(env.from)) {
            rejectBlocked(env)
            return
        }

        // 注意：key == null 表示「不参与去重」，**不是**「丢弃」。
        // 早期写法是 `dedupeKey(env) ?: return`，把没列进去重表的包类型（撤回/双方删除）
        // 直接在入口吞掉了，真机上表现为「撤回/删除只在本机生效」。
        val key = dedupeKey(env)
        if (key != null && !markSeen(key)) return

        if (relayIfNeeded(conn, env)) return
        dispatch(conn, env)
    }

    /**
     * 好友认证的线上格式：
     *
     * * 申请 = [FriendRequestPacket]（`hello` 是验证消息）；
     * * 同意 = [FriendAcceptPacket]，带上同意方的名字与头像种子，**双方都要写联系人**；
     * * 拒绝 = [FriendRejectPacket]。
     *
     * 另外一条兼容路径：陌生人第一次直接发**私聊文本**时（他还没走申请流程），
     * 接收方也把它登记成一条好友申请，而不是聊天消息。
     */
    /** 收到非好友的私聊消息：不进会话列表，转成一条好友申请。 */
    private fun registerFriendRequest(deviceId: String, conn: Connection, hello: String, reqId: String) {
        val cur = friendRequestsState.value
        if (cur.any { it.deviceId == deviceId }) return
        val contact = store.contact(deviceId)
        val name = contact?.display?.takeIf { it.isNotBlank() }
            ?: conn.remoteName.takeIf { it.isNotBlank() }
            ?: deviceId.take(8)
        friendRequestsState.value = cur + FriendRequest(
            deviceId = deviceId,
            name = name,
            avatarSeed = contact?.avatarSeed ?: conn.remoteSeed,
            hello = hello,
            reqId = reqId
        )
        onNotice?.invoke("收到好友申请：$name")
    }

    private fun onFriendRequest(conn: Connection, env: Envelope, p: FriendRequestPacket) {
        val id = p.fromId.ifBlank { env.from }
        if (id.isBlank() || isFriend(id)) return
        val cur = friendRequestsState.value
        if (cur.any { it.deviceId == id }) return
        friendRequestsState.value = cur + FriendRequest(
            deviceId = id,
            name = p.fromName.takeIf { it.isNotBlank() }
                ?: conn.remoteName.takeIf { it.isNotBlank() }
                ?: id.take(8),
            avatarSeed = if (p.avatarSeed != 0) p.avatarSeed else conn.remoteSeed,
            hello = p.hello
        )
        onNotice?.invoke("收到好友申请：${p.fromName.ifBlank { id.take(8) }}")
    }

    /** 同意一条好友申请：写入联系人，并回执让对方也写入。 */
    fun acceptFriend(deviceId: String): Boolean {
        val req = friendRequestsState.value.firstOrNull { it.deviceId == deviceId } ?: return false
        friendRequestsState.value = friendRequestsState.value.filterNot { it.deviceId == deviceId }
        val me = settings.ensureIdentity()
        val existing = store.contact(deviceId)
        store.saveContact(
            Contact(
                deviceId = deviceId,
                name = existing?.name?.takeIf { it.isNotBlank() } ?: req.name,
                remark = existing?.remark ?: "",
                address = byDevice[deviceId]?.address ?: existing?.address ?: "",
                avatarSeed = existing?.avatarSeed?.takeIf { it != 0 } ?: req.avatarSeed,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis()
            )
        )
        syncDirectConvTitle(deviceId)
        sendPacketTo(
            deviceId,
            null,
            FriendAcceptPacket(me.myDeviceId, me.myName, me.myAvatarSeed)
        )
        onNotice?.invoke("已同意 ${req.name} 的好友申请")
        return true
    }

    /** 拒绝一条好友申请。 */
    fun rejectFriend(deviceId: String): Boolean {
        val req = friendRequestsState.value.firstOrNull { it.deviceId == deviceId } ?: return false
        friendRequestsState.value = friendRequestsState.value.filterNot { it.deviceId == deviceId }
        sendPacketTo(deviceId, null, FriendRejectPacket(myId(), "declined"))
        onNotice?.invoke("已拒绝 ${req.name} 的好友申请")
        return true
    }

    /**
     * 主动向对方发起好友申请（附带一句验证消息）。
     * [peerId] 可以是 deviceId，也可以是 MAC（内部会解析）。
     */
    fun requestFriendship(peerId: String, hello: String = ""): Boolean {
        val target = resolvePeerId(peerId)
        if (target.isBlank()) return false
        val me = settings.ensureIdentity()
        val ok = sendTo(
            target,
            Envelope(
                from = me.myDeviceId,
                to = target,
                packet = FriendRequestPacket(me.myDeviceId, me.myName, me.myAvatarSeed, hello)
            )
        )
        if (ok) onNotice?.invoke("已向 ${store.contact(target)?.display ?: target.take(8)} 发送好友申请")
        return ok
    }

    /** 对方同意了我的申请：写入联系人（「双方都要写」的另一半）。 */
    private fun onFriendAccept(conn: Connection, env: Envelope, p: FriendAcceptPacket) {
        val id = p.fromId.ifBlank { env.from }
        if (id.isBlank()) return
        val existing = store.contact(id)
        store.saveContact(
            Contact(
                deviceId = id,
                name = p.name.takeIf { it.isNotBlank() }
                    ?: existing?.name?.takeIf { it.isNotBlank() }
                    ?: conn.remoteName.takeIf { it.isNotBlank() }
                    ?: id.take(8),
                remark = existing?.remark ?: "",
                address = conn.address.ifBlank { existing?.address ?: "" },
                avatarSeed = if (p.avatarSeed != 0) p.avatarSeed else (existing?.avatarSeed ?: conn.remoteSeed),
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis()
            )
        )
        friendRequestsState.value = friendRequestsState.value.filterNot { it.deviceId == id }
        syncDirectConvTitle(id)
        onNotice?.invoke("${store.contact(id)?.display ?: id.take(8)} 已同意你的好友申请")
    }

    private fun onFriendReject(conn: Connection, env: Envelope, p: FriendRejectPacket) {
        val id = p.fromId.ifBlank { env.from }
        friendRequestsState.value = friendRequestsState.value.filterNot { it.deviceId == id }
        onNotice?.invoke("对方拒绝了你的好友申请（${store.contact(id)?.display ?: id.take(8)}）")
    }

    // ------------------------------------------------------------ 拉黑 / 好友

    /** 带 TTL 缓存的拉黑判断：入站每个包都要问一次，不能每次都去读 SharedPreferences。 */
    fun isBlocked(deviceId: String): Boolean {
        if (deviceId.isBlank()) return false
        val now = System.currentTimeMillis()
        val set = synchronized(blockLock) {
            if (now - blockCachedAt > BtConstants.BLOCK_CACHE_TTL_MS) {
                blockCached = runCatching { blockList() }.getOrDefault(emptySet())
                blockCachedAt = now
            }
            blockCached
        }
        return set.isNotEmpty() && deviceId in set
    }

    /** 会被拉黑规则拦下的包类型（内容与请求）；协议回执允许通过，免得对端状态卡住。 */
    private fun isBlockablePacket(p: Packet): Boolean = when (p) {
        is TextPacket, is TypingPacket, is FileOfferPacket,
        is CallInvitePacket, is GroupInvitePacket, is ProfilePacket -> true
        else -> false
    }

    /** 告诉发送方「你被拒收了」，否则他一直以为消息发成功了。 */
    private fun rejectBlocked(env: Envelope) {
        when (val p = env.packet) {
            is TextPacket -> replyTo(env, ReceiptPacket(p.msgId, p.convId, RECEIPT_BLOCKED))
            is FileOfferPacket -> replyTo(env, ReceiptPacket(p.msgId, p.convId, RECEIPT_BLOCKED))
            is CallInvitePacket -> replyTo(env, CallRejectPacket(p.callId, "blocked"))
            else -> Unit
        }
        onNotice?.invoke("已拦截黑名单设备 ${env.from.take(8)} 的消息")
    }

    /** 「好友」= 联系人表里有这个人。连接本身不等于加好友。 */
    fun isFriend(deviceId: String): Boolean = deviceId.isNotBlank() && store.contact(deviceId) != null

    /**
     * 群主中继。返回 true 表示这个包已经被转发给别的设备（或作为环路被丢弃），
     * 本机不再处理。
     */
    private fun relayIfNeeded(conn: Connection, env: Envelope): Boolean {
        val groupId = env.groupId ?: return false
        if (env.hop > BtConstants.RELAY_MAX_HOP) return true // 防环：已经中转过的广播不再转发

        if (!isOwner(groupId)) return false

        if (env.to != null && env.to != myId()) {
            // 定向中转：成员 A 想给成员 B 发东西，群主代转，本机不做业务处理
            (env.packet as? FileAcceptPacket)?.let { relayRoutes[it.transferId]?.accepted?.add(env.from) }
            sendTo(env.to, env.copy(hop = env.hop + 1))
            return true
        }

        if (env.to == null) {
            // 通话邀请**不在这里扇出**：CallController 收到后会用「除自己与发起人以外的
            // 成员」精确投递一次。如果 Router 也扇一遍，成员会收到两份邀请（两份的
            // env.from 不同，去重表拦不住），第二份会被状态机当成「忙线」拒接，
            // 直接把刚建立的群通话打掉。
            if (env.packet is CallInvitePacket) return false
            val targets = neighbors(groupId).filter { it !== conn && it.remoteId != env.from }
            (env.packet as? FileOfferPacket)?.let {
                if (targets.isNotEmpty()) relayRoutes[it.transferId] = RelayRoute(conn, targets.toMutableList())
            }
            for (t in targets) t.send(env.copy(hop = env.hop + 1))
        }
        return false
    }

    private fun dispatch(conn: Connection, env: Envelope) {
        val tm = if (::transfers.isInitialized) transfers else null
        when (val p = env.packet) {
            is TextPacket -> onText(conn, env, p)
            is ReceiptPacket -> onReceipt(conn, env, p)
            is TypingPacket -> onTyping(conn, env, p)
            is GroupInvitePacket -> onGroupInvite(env, p)
            is GroupUpdatePacket -> onGroupUpdate(env, p)
            is ProfilePacket -> onProfile(conn, p)
            is RecallPacket -> store.recallMessage(p.msgId)
            // 双方删除：对端消息连同它的附件文件一起消失，否则磁盘上会留一份
            // 用户以为已经删掉的内容
            is DeleteMessagePacket -> {
                store.message(p.msgId)?.attachment?.let { att ->
                    runCatching { media.deleteAttachmentFiles(att) }
                }
                store.deleteMessage(p.msgId)
            }
            is FileOfferPacket -> tm?.onOffer(conn, env, p)
            is FileAcceptPacket -> {
                relayRoutes[p.transferId]?.accepted?.add(env.from)
                tm?.onAccept(conn, env, p)
            }
            is FileRejectPacket -> tm?.onReject(conn, env, p)
            is FileDonePacket -> tm?.onDone(conn, env, p)
            is FileAbortPacket -> tm?.onAbort(conn, env, p)
            // ---- 通话信令：交给 CallController 的状态机 ----
            is CallInvitePacket -> callSignaling?.onInvite(conn, env, p)
            is CallAcceptPacket -> callSignaling?.onAccept(conn, env, p)
            is CallRejectPacket -> callSignaling?.onReject(conn, env, p)
            is CallEndPacket -> callSignaling?.onEnd(conn, env, p)
            is CallStatePacket -> callSignaling?.onPeerState(conn, env, p)
            // ---- 好友认证 ----
            is FriendRequestPacket -> onFriendRequest(conn, env, p)
            is FriendAcceptPacket -> onFriendAccept(conn, env, p)
            is FriendRejectPacket -> onFriendReject(conn, env, p)
            // ---- 离线中转：只保管/投递，绝不写进会话 ----
            is RelayEnvelopePacket -> if (::relay.isInitialized) relay.onEnvelopePacket(conn, env, p)
            is RelayAckPacket -> if (::relay.isInitialized) relay.onAck(conn, p)
            else -> Unit
        }
    }

    // ------------------------------------------------------------- 业务分派

    private fun onText(conn: Connection, env: Envelope, p: TextPacket) {
        val existing = store.message(p.msgId)
        if (existing?.outgoing == true) return
        // 陌生人 / 已被删除的好友发来的**私聊**消息：不进会话列表，转成好友申请。
        // 群聊消息不受好友关系影响。
        if (env.groupId == null && !isFriend(env.from)) {
            registerFriendRequest(env.from, conn, p.text, p.msgId)
            return
        }
        if (existing == null) {
            val conv = incomingConv(env, p.convId, conn)
            val from = peerDisplayName(env.from, conn)
            store.saveMessage(
                Message(
                    id = p.msgId,
                    convId = conv.id,
                    senderId = env.from,
                    senderName = from,
                    kind = MsgKind.TEXT,
                    text = p.text,
                    sentAt = if (p.at > 0) p.at else System.currentTimeMillis(),
                    state = MsgState.DELIVERED,
                    outgoing = false,
                    quote = p.quote?.toModel()
                )
            )
            // Only for a genuinely new message, so a re-delivery does not ring twice.
            onIncomingNotice?.invoke(conv.id, from, p.text)
        }
        // 送达回执沿原路返回（群聊里由群主代转）
        replyTo(env, ReceiptPacket(p.msgId, p.convId, MsgState.DELIVERED.name))
    }

    private fun onReceipt(conn: Connection, env: Envelope, p: ReceiptPacket) {
        // 被对方拉黑：这条消息对方根本没看，如实置失败并提示
        if (p.state == RECEIPT_BLOCKED) {
            if (store.message(p.msgId)?.outgoing == true) {
                store.setMessageState(p.msgId, MsgState.FAILED)
                onNotice?.invoke("消息被对方拒收（可能已被拉黑）")
            }
            return
        }
        val m = store.message(p.msgId) ?: return
        if (!m.outgoing) return
        val incoming = runCatching { MsgState.valueOf(p.state) }.getOrDefault(MsgState.DELIVERED)
        val target = when (incoming) {
            MsgState.DRAFT, MsgState.SENDING, MsgState.FAILED -> MsgState.DELIVERED
            else -> incoming
        }
        val state = if (rank(target) > rank(m.state)) target else m.state
        val readBy = if (target == MsgState.READ && !m.readBy.contains(env.from)) m.readBy + env.from else m.readBy
        if (readBy != m.readBy) {
            // readBy 只能整条重写；saveMessage 会顺手把会话摘要改成这条消息的，
            // 所以补一次摘要修正（见 repairSummary）
            store.saveMessage(m.copy(state = state, readBy = readBy))
            repairSummary(m.convId)
        } else if (state != m.state) {
            // 只改状态时走 setMessageState，避免触碰 unread / 摘要
            store.setMessageState(m.id, state)
        }
    }

    private fun onTyping(conn: Connection, env: Envelope, p: TypingPacket) {
        val convId = runCatching { incomingConv(env, p.convId, conn).id }.getOrNull() ?: return
        if (p.on) typingUntil[convId] = System.currentTimeMillis() + BtConstants.TYPING_TTL_MS
        else typingUntil.remove(convId)
        publishTyping()
    }

    private fun onGroupInvite(env: Envelope, p: GroupInvitePacket) {
        val me = myId()
        val prev = store.conversation(p.groupId)
        val members = ArrayList<Member>()
        members.add(
            Member(
                deviceId = me,
                name = myName(),
                avatarSeed = mySeed(),
                role = if (p.ownerId == me) Role.OWNER else Role.MEMBER
            )
        )
        for (dto in p.members) {
            val m = dto.toModel()
            if (m.deviceId.isNotBlank() && m.deviceId != me) members.add(m)
        }
        store.saveConversation(
            Conversation(
                id = p.groupId,
                kind = ConvKind.GROUP,
                title = p.name.ifBlank { "群聊" },
                avatarSeed = if (p.avatarSeed != 0) p.avatarSeed else seedOf(p.groupId),
                lastMessageAt = prev?.lastMessageAt ?: 0L,
                lastPreview = prev?.lastPreview ?: "",
                unread = prev?.unread ?: 0,
                pinned = prev?.pinned ?: false,
                muted = prev?.muted ?: false,
                iAmOwner = p.ownerId == me,
                memberCount = members.size
            )
        )
        store.setMembers(p.groupId, members)
        groupRevision[p.groupId] = System.currentTimeMillis()
        if (prev == null) {
            systemMessage(p.groupId, "你已加入群聊「${p.name.ifBlank { "群聊" }}」")
        }
        onNotice?.invoke("群聊「${p.name}」成员已更新")
    }

    private fun onGroupUpdate(env: Envelope, p: GroupUpdatePacket) {
        val prev = store.conversation(p.groupId)
        if (p.revision != REV_DISSOLVE) {
            val last = groupRevision[p.groupId]
            if (last != null && p.revision in 1L until last) return // 乱序的旧更新
            if (p.revision > 0) groupRevision[p.groupId] = p.revision
        }
        if (p.revision == REV_DISSOLVE) {
            if (prev != null) {
                systemMessage(p.groupId, "群聊已解散")
                store.deleteConversation(p.groupId)
            }
            groupRevision.remove(p.groupId)
            onNotice?.invoke("群聊已解散")
            return
        }
        if (prev == null) {
            // 先收到更新再收到邀请的极端情况：先把壳建出来，等邀请补成员
            store.saveConversation(
                Conversation(
                    id = p.groupId,
                    kind = ConvKind.GROUP,
                    title = p.name.ifBlank { "群聊" },
                    avatarSeed = if (p.avatarSeed != 0) p.avatarSeed else seedOf(p.groupId),
                    iAmOwner = false
                )
            )
        } else if (p.name.isNotBlank() || p.avatarSeed != 0) {
            store.saveConversation(
                prev.copy(
                    title = if (p.name.isNotBlank()) p.name else prev.title,
                    avatarSeed = if (p.avatarSeed != 0) p.avatarSeed else prev.avatarSeed
                )
            )
        }
        if (p.members.isNotEmpty()) {
            val me = myId()
            val list = p.members.map { it.toModel() }.filter { it.deviceId.isNotBlank() }
            if (list.none { it.deviceId == me }) {
                // 成员表里没有自己 —— 说明已被移出群聊
                systemMessage(p.groupId, "你已被移出群聊")
                store.deleteConversation(p.groupId)
                return
            }
            store.setMembers(p.groupId, list)
        }
        onLinksChanged?.invoke()
    }

    private fun onProfile(conn: Connection, p: ProfilePacket) {
        val id = p.deviceId.ifBlank { conn.remoteId }
        if (id.isBlank()) return
        // 只更新**已存在**的联系人：资料包不是「加好友」。
        // 以前这里无条件 saveContact，等于每次连接都把人加回联系人表，
        // 删掉的好友会被悄悄恢复（用户实测「删了好友还能照常发消息」的一个入口）。
        val existing = store.contact(id) ?: return
        store.saveContact(
            existing.copy(
                name = p.name.ifBlank { existing.name },
                address = conn.address.ifBlank { existing.address },
                avatarSeed = if (p.avatarSeed != 0) p.avatarSeed else existing.avatarSeed,
                lastSeen = System.currentTimeMillis()
            )
        )
        syncDirectConvTitle(id)
        onLinksChanged?.invoke()
    }

    // --------------------------------------------------------------- 发送端

    /** 发一个包给指定设备：优先直连，没有直连就请群主代转。 */
    fun sendTo(deviceId: String, env: Envelope): Boolean {
        if (deviceId.isBlank()) return false
        val direct = byDevice[deviceId]
        if (direct != null && direct.isReady && !direct.isClosed) return direct.send(env)

        val groupId = env.groupId ?: return false
        val ownerId = groupOwnerId(groupId) ?: return false
        if (ownerId == myId()) return false
        val ownerConn = byDevice[ownerId] ?: return false
        if (!ownerConn.isReady || ownerConn.isClosed) return false
        return ownerConn.send(env.copy(to = deviceId))
    }

    /** 发一个包给指定设备（自带 Envelope 头）。 */
    fun sendPacketTo(deviceId: String, groupId: String?, packet: Packet): Boolean = sendTo(
        deviceId,
        Envelope(from = myId(), to = deviceId, groupId = groupId, hop = 0, packet = packet)
    )

    /** 回应某个收到的包（回给发送者）。 */
    fun replyTo(env: Envelope, packet: Packet): Boolean = sendPacketTo(env.from, env.groupId, packet)

    /**
     * 向会话的所有对端投递一个包，返回**已处置**的目标数（直接发出 + 交给中继保管）。
     *
     * * 单聊：优先直连；连不上就交给中继（只要有一个对端在线，消息就能上路）。
     * * 群聊且本机是群主：直接发给每个成员，发不出去的交给中继。
     * * 群聊且本机是成员：有群主链路就交给群主广播（群主负责扇出），
     *   同时把**离线成员**做成中继信封交出去；没有群主链路就退化为向直连成员发送。
     */
    fun broadcast(convId: String, packet: Packet): Int {
        val conv = store.conversation(convId) ?: return 0
        val me = myId()
        val relayable = com.liuli.btchat.bt.relay.RelayService.relayMsgId(packet) != null
        if (conv.kind == ConvKind.DIRECT) {
            val peer = resolvePeerId(conv.peerId ?: return 0)
            if (sendPacketTo(peer, null, packet)) return 1
            return if (relayable && stashFor(convId, null, packet, listOf(peer))) 1 else 0
        }
        val env = Envelope(from = me, to = null, groupId = convId, hop = 0, packet = packet)
        val members = store.members(convId).map { it.deviceId }
            .filter { it.isNotBlank() && it != me }.distinct()
        var handled = 0
        val undelivered = ArrayList<String>()
        if (conv.iAmOwner) {
            for (m in members) if (sendTo(m, env)) handled++ else undelivered.add(m)
        } else {
            val ownerId = groupOwnerId(convId)
            if (ownerId != null && ownerId != me && sendTo(ownerId, env)) {
                // 群主收到后会替我们扇出给在线成员；离线成员只能靠存储转发补齐
                handled++
                for (m in members) if (m != ownerId && !isOnline(m)) undelivered.add(m)
            } else {
                for (m in members) if (sendTo(m, env)) handled++ else undelivered.add(m)
            }
        }
        if (relayable && undelivered.isNotEmpty()) {
            if (stashFor(convId, convId, packet, undelivered) > 0) handled++
        }
        return handled
    }

    /** 交给中继保管：把包分发给「当前已连的对端」，请它们帮忙带到 [dests]。 */
    private fun stashFor(convId: String, groupId: String?, packet: Packet, dests: List<String>): Int {
        if (!::relay.isInitialized) return 0
        return runCatching { relay.stash(convId, groupId, packet, dests, myId()) }.getOrDefault(0)
    }

    /** 会话的所有潜在对端 deviceId。 */
    fun targetsForConv(conv: Conversation): List<String> {
        val me = myId()
        return when (conv.kind) {
            ConvKind.DIRECT -> listOfNotNull(conv.peerId?.takeIf { it.isNotBlank() })
            ConvKind.GROUP -> store.members(conv.id).map { it.deviceId }
                .filter { it.isNotBlank() && it != me }.distinct()
        }
    }

    // -------------------------------------------------------------- 发消息

    /** 发一条文本；先乐观落库（SENDING），有链路则标记 SENT，对端回执后转 DELIVERED。 */
    fun sendText(convId: String, text: String, quoteMsgId: String? = null): Message? {
        if (text.isBlank()) return null
        val conv = store.conversation(convId) ?: return null
        val me = settings.ensureIdentity()
        val quoted = quoteMsgId?.let { id ->
            store.message(id)?.let { q ->
                Quote(
                    msgId = q.id,
                    senderName = if (q.outgoing) me.myName else q.senderName.ifBlank { "对方" },
                    preview = q.preview.take(60)
                )
            }
        }
        val msg = Message(
            id = newId(),
            convId = convId,
            senderId = me.myDeviceId,
            senderName = me.myName,
            kind = MsgKind.TEXT,
            text = text,
            sentAt = System.currentTimeMillis(),
            state = MsgState.SENDING,
            outgoing = true,
            quote = quoted
        )
        store.saveMessage(msg)
        // 对方还不是好友：私聊只能发好友申请，消息本身不投递（对方会被拦成「好友申请」）。
        // 群聊不受好友关系影响。
        val directPeer = if (conv.kind == ConvKind.DIRECT) conv.peerId?.let { resolvePeerId(it) } else null
        if (directPeer != null && directPeer.isNotBlank() && !isFriend(directPeer)) {
            val requested = requestFriendship(directPeer, text)
            store.setMessageState(msg.id, MsgState.FAILED)
            onNotice?.invoke(
                if (requested) "对方还不是你的好友，已发送好友申请" else "对方还不是你的好友，且当前没有链路"
            )
            return store.message(msg.id)
        }
        val sent = broadcast(
            convId,
            TextPacket(convId, msg.id, text, msg.sentAt, quoted?.let { QuoteDto.of(it) })
        )
        store.setMessageState(msg.id, if (sent > 0) MsgState.SENT else MsgState.FAILED)
        return store.message(msg.id)
    }

    /**
     * Takes back a message we sent. Local first so the UI reacts at once, then
     * the peer is told to tombstone its copy.
     */
    fun recall(messageId: String) {
        val m = store.message(messageId) ?: return
        if (!m.outgoing) return
        store.recallMessage(messageId)
        broadcast(m.convId, RecallPacket(m.convId, messageId, System.currentTimeMillis()))
    }

    /** Deletes on both sides — the peer drops the row entirely. */
    fun deleteForEveryone(messageId: String) {
        val m = store.message(messageId) ?: return
        store.deleteMessage(messageId)
        broadcast(m.convId, DeleteMessagePacket(m.convId, messageId))
    }

    /**
     * Copies a message into another chat.
     *
     * Text is re-sent as a new message. Media is re-offered from the copy we
     * already hold on disk, so forwarding a photo never re-imports it.
     */
    fun forward(messageId: String, toConvId: String): Message? {
        val src = store.message(messageId) ?: return null
        if (store.conversation(toConvId) == null) return null
        val me = settings.ensureIdentity()
        return when {
            src.recalled -> null

            src.attachment != null -> {
                val imported = ImportedMedia(
                    filePath = src.attachment.localPath.orEmpty(),
                    thumbPath = src.attachment.thumbPath,
                    thumbB64 = src.attachment.thumbB64,
                    name = src.attachment.fileName,
                    mime = src.attachment.mime,
                    size = src.attachment.size,
                    width = src.attachment.width,
                    height = src.attachment.height,
                    durationMs = src.attachment.durationMs,
                    sha256 = src.attachment.sha256
                )
                if (imported.filePath.isBlank()) null else mediaSender?.invoke(toConvId, imported)
            }

            else -> {
                val msg = Message(
                    id = newId(),
                    convId = toConvId,
                    senderId = me.myDeviceId,
                    senderName = me.myName,
                    kind = MsgKind.TEXT,
                    text = src.text,
                    sentAt = System.currentTimeMillis(),
                    state = MsgState.SENDING,
                    outgoing = true
                )
                store.saveMessage(msg)
                val sent = broadcast(toConvId, TextPacket(toConvId, msg.id, src.text, msg.sentAt))
                store.setMessageState(msg.id, if (sent > 0) MsgState.SENT else MsgState.FAILED)
                store.message(msg.id)
            }
        }
    }

    fun setStarred(messageId: String, starred: Boolean) {
        store.setStarred(messageId, starred)
    }

    /** 重发一条消息（文本走原路径，附件交给 [TransferManager]）。 */
    fun resend(messageId: String) {
        val m = store.message(messageId) ?: return
        if (!m.outgoing) return
        if (m.attachment != null) {
            transfers.resend(m)
            return
        }
        val conv = store.conversation(m.convId) ?: return
        store.setMessageState(messageId, MsgState.SENDING)
        val sent = broadcast(conv.id, TextPacket(conv.id, m.id, m.text, m.sentAt))
        store.setMessageState(messageId, if (sent > 0) MsgState.SENT else MsgState.FAILED)
    }

    fun setTyping(convId: String, on: Boolean) {
        val conv = store.conversation(convId) ?: return
        broadcast(convId, TypingPacket(convId, on))
    }

    /**
     * 标记会话已读，并把 READ 回执发给每条收到的消息的发送者。
     *
     * 注意：收到的消息**只 saveMessage 一次**（Store 会累加 unread），这里只用
     * [ChatStore.setMessageState] 推进状态，所以不会重复计入未读，也不会把会话
     * 摘要改写成一条旧消息。
     */
    fun markRead(convId: String) {
        val me = myId()
        val conv = store.conversation(convId) ?: return
        val groupId = if (conv.kind == ConvKind.GROUP) conv.id else null
        val pending = store.messages(convId, 50).filter {
            !it.outgoing && it.senderId != me && it.kind != MsgKind.SYSTEM && it.state != MsgState.READ
        }
        for (m in pending) {
            store.setMessageState(m.id, MsgState.READ)
            sendPacketTo(m.senderId, groupId, ReceiptPacket(m.id, convId, MsgState.READ.name))
        }
        store.markConversationRead(convId)
    }

    /** 把会话摘要修正为「最新一条消息」（回执路径重写消息时需要用）。 */
    private fun repairSummary(convId: String) {
        val latest = store.latestMessage(convId) ?: return
        val conv = store.conversation(convId) ?: return
        val at = maxOf(conv.lastMessageAt, latest.sentAt)
        if (conv.lastPreview != latest.preview || conv.lastMessageAt != at) {
            store.saveConversation(conv.copy(lastPreview = latest.preview, lastMessageAt = at))
        }
    }

    // -------------------------------------------------------------- 会话解析

    /**
     * 把收到的包映射成本机会话。
     *
     * 关键点：单聊的 `convId` 是发送方自己数据库里的主键，对端并不知道，
     * 所以要用 `env.from` 反查本机与该设备的会话；群聊的 `convId` 就是
     * `groupId`，全局一致，直接使用。
     */
    fun incomingConv(env: Envelope, hintConvId: String?, conn: Connection): Conversation {
        val groupId = env.groupId
        if (groupId != null) return ensureGroupConv(groupId, env.from, conn)
        return ensureDirectConv(env.from, peerDisplayName(env.from, conn), conn.remoteSeed, hintConvId)
    }

    /** 取（必要时创建）与某设备的单聊会话。 */
    fun ensureDirectConv(
        peerId: String,
        peerName: String = "",
        avatarSeed: Int = 0,
        hintConvId: String? = null
    ): Conversation {
        val key = resolvePeerId(peerId)
        if (hintConvId != null) {
            val hinted = store.conversation(hintConvId)
            if (hinted != null && hinted.kind == ConvKind.DIRECT && hinted.peerId == key) return hinted
        }
        store.conversations().firstOrNull { it.kind == ConvKind.DIRECT && it.peerId == key }?.let { return it }
        val contact = store.contact(key)
        val conv = Conversation(
            id = newId(),
            kind = ConvKind.DIRECT,
            title = contact?.display?.takeIf { it.isNotBlank() }
                ?: peerName.takeIf { it.isNotBlank() }
                ?: key.take(8),
            peerId = key,
            avatarSeed = contact?.avatarSeed?.takeIf { it != 0 }
                ?: avatarSeed.takeIf { it != 0 }
                ?: seedOf(key)
        )
        store.saveConversation(conv)
        return conv
    }

    /**
     * A chat opened from the discover screen before the handshake finished is
     * keyed by the Bluetooth address, while everything the peer sends afterwards
     * arrives under its device id. Left alone those become two separate chats —
     * you type into one and the replies land in the other — so the address is
     * resolved to a device id as soon as the link knows one.
     */
    private fun resolvePeerId(peerId: String): String {
        if (peerId.isBlank() || !looksLikeAddress(peerId)) return peerId
        val conn = byAddress[peerId] ?: return peerId
        return conn.remoteId.takeIf { it.isNotBlank() } ?: peerId
    }

    /**
     * Re-keys (and, if needed, merges) direct conversations that were created
     * against a Bluetooth address once the peer has introduced itself.
     */
    private fun migrateDirectConv(address: String, deviceId: String) {
        if (address.isBlank() || deviceId.isBlank() || address == deviceId) return
        val keyed = store.conversations().filter { it.kind == ConvKind.DIRECT && it.peerId == address }
        if (keyed.isEmpty()) return
        val contact = store.contact(deviceId)
        val target = store.conversations()
            .firstOrNull { it.kind == ConvKind.DIRECT && it.peerId == deviceId }
        for (conv in keyed) {
            if (target != null && target.id != conv.id) {
                // Fold the earlier chat into the one that already exists.
                // 合并时逐条 saveMessage 会把每条「收到的消息」再算一次未读，
                // 目标是已有会话，未读数会被凭空抬高 —— 合并完把未读恢复成原值。
                val unreadBefore = target.unread
                store.messages(conv.id, Int.MAX_VALUE).forEach { m ->
                    store.saveMessage(m.copy(convId = target.id))
                }
                store.deleteConversation(conv.id)
                store.conversation(target.id)?.let { merged ->
                    if (merged.unread != unreadBefore) {
                        store.saveConversation(merged.copy(unread = unreadBefore))
                    }
                }
            } else {
                store.saveConversation(
                    conv.copy(
                        peerId = deviceId,
                        title = contact?.display?.takeIf { it.isNotBlank() } ?: conv.title,
                        avatarSeed = contact?.avatarSeed?.takeIf { it != 0 } ?: conv.avatarSeed
                    )
                )
            }
        }
    }

    private fun looksLikeAddress(value: String): Boolean =
        value.length == 17 && value.count { it == ':' } == 5

    /** 取（必要时创建）群聊会话。 */
    fun ensureGroupConv(groupId: String, fromId: String = "", conn: Connection? = null): Conversation {
        store.conversation(groupId)?.let { return it }
        val me = myId()
        val conv = Conversation(
            id = groupId,
            kind = ConvKind.GROUP,
            title = "群聊",
            avatarSeed = seedOf(groupId),
            iAmOwner = false
        )
        store.saveConversation(conv)
        if (fromId.isNotBlank() && fromId != me) {
            store.addMember(
                groupId,
                Member(
                    deviceId = fromId,
                    name = conn?.remoteName.orEmpty().ifBlank { fromId.take(8) },
                    avatarSeed = conn?.remoteSeed ?: 0
                )
            )
            store.addMember(groupId, Member(me, myName(), mySeed(), Role.MEMBER))
        }
        return conv
    }

    /** UI 用：与某个联系人建立/复用单聊。 */
    fun directConversationWith(contact: Contact): Conversation =
        ensureDirectConv(contact.deviceId, contact.display, contact.avatarSeed)

    /** UI 用：与某个蓝牙设备建立/复用单聊。 */
    fun ensureDirectConversation(peer: Peer): Conversation =
        ensureDirectConv(peer.key, peer.name, peer.avatarSeed, hintConvId = null)

    // ------------------------------------------------------------------ 群

    fun createGroup(name: String, avatarSeed: Int, members: List<Contact>): Conversation? {
        if (members.isEmpty()) return null
        val me = settings.ensureIdentity()
        val id = newId()
        val conv = Conversation(
            id = id,
            kind = ConvKind.GROUP,
            title = name.ifBlank { "群聊" },
            avatarSeed = if (avatarSeed != 0) avatarSeed else seedOf(id),
            iAmOwner = true,
            memberCount = members.size + 1
        )
        store.saveConversation(conv)
        val list = ArrayList<Member>()
        list.add(Member(me.myDeviceId, me.myName, me.myAvatarSeed, Role.OWNER))
        for (c in members) list.add(Member(c.deviceId, c.display, c.avatarSeed, Role.MEMBER))
        store.setMembers(id, list)
        groupRevision[id] = System.currentTimeMillis()
        systemMessage(id, "群聊已创建")
        val invite = GroupInvitePacket(
            groupId = id,
            name = conv.title,
            avatarSeed = conv.avatarSeed,
            ownerId = me.myDeviceId,
            members = list.map { MemberDto.of(it) }
        )
        for (c in members) sendPacketTo(c.deviceId, id, invite)
        return conv
    }

    fun renameGroup(groupId: String, name: String) {
        val conv = store.conversation(groupId) ?: return
        store.saveConversation(conv.copy(title = name.ifBlank { conv.title }))
        broadcastUpdate(groupId)
    }

    fun addGroupMember(groupId: String, contact: Contact) {
        val conv = store.conversation(groupId) ?: return
        store.addMember(groupId, Member(contact.deviceId, contact.display, contact.avatarSeed, Role.MEMBER))
        val me = settings.ensureIdentity()
        val members = store.members(groupId)
        sendPacketTo(
            contact.deviceId,
            groupId,
            GroupInvitePacket(
                groupId = groupId,
                name = conv.title,
                avatarSeed = conv.avatarSeed,
                ownerId = members.firstOrNull { it.role == Role.OWNER }?.deviceId ?: me.myDeviceId,
                members = members.map { MemberDto.of(it) }
            )
        )
        broadcastUpdate(groupId)
    }

    fun removeGroupMember(groupId: String, deviceId: String) {
        store.removeMember(groupId, deviceId)
        broadcastUpdate(groupId)
    }

    /** 非群主退群：把「我退出后的成员表」同步给群主，然后删掉本地会话。 */
    fun leaveGroup(groupId: String) {
        val conv = store.conversation(groupId) ?: return
        val me = myId()
        if (conv.iAmOwner) {
            dissolveGroup(groupId)
            return
        }
        val remaining = store.members(groupId).filter { it.deviceId != me }
        val ownerId = groupOwnerId(groupId)
        if (ownerId != null) {
            sendPacketTo(
                ownerId,
                groupId,
                GroupUpdatePacket(
                    groupId = groupId,
                    name = conv.title,
                    avatarSeed = conv.avatarSeed,
                    revision = System.currentTimeMillis(),
                    members = remaining.map { MemberDto.of(it) }
                )
            )
        }
        store.deleteConversation(groupId)
    }

    /** 群主解散群：广播 REV_DISSOLVE，然后删掉本地会话。 */
    fun dissolveGroup(groupId: String) {
        val conv = store.conversation(groupId) ?: return
        broadcast(
            groupId,
            GroupUpdatePacket(
                groupId = groupId,
                name = conv.title,
                avatarSeed = conv.avatarSeed,
                revision = REV_DISSOLVE,
                members = emptyList()
            )
        )
        store.deleteConversation(groupId)
        groupRevision.remove(groupId)
    }

    private fun broadcastUpdate(groupId: String) {
        val conv = store.conversation(groupId) ?: return
        val rev = System.currentTimeMillis()
        groupRevision[groupId] = rev
        broadcast(
            groupId,
            GroupUpdatePacket(
                groupId = groupId,
                name = conv.title,
                avatarSeed = conv.avatarSeed,
                revision = rev,
                members = store.members(groupId).map { MemberDto.of(it) }
            )
        )
    }

    fun isOwner(groupId: String): Boolean = store.conversation(groupId)?.iAmOwner == true

    /** 群主 deviceId：优先取成员表里的 OWNER，其次看本地会话的 iAmOwner。 */
    fun groupOwnerId(groupId: String): String? {
        store.members(groupId).firstOrNull { it.role == Role.OWNER }?.deviceId
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return if (isOwner(groupId)) myId() else null
    }

    /** 本机在该群里的其他成员链路（群主扇出用）。 */
    private fun neighbors(groupId: String): List<Connection> {
        val me = myId()
        val ids = store.members(groupId).map { it.deviceId }.filter { it.isNotBlank() && it != me }
        val result = ArrayList<Connection>(ids.size)
        for (id in ids) byDevice[id]?.takeIf { it.isReady && !it.isClosed }?.let { result.add(it) }
        return result
    }

    // ------------------------------------------------------------------ 工具

    private fun systemMessage(convId: String, text: String) {
        store.saveMessage(
            Message(
                id = newId(),
                convId = convId,
                senderId = "system",
                senderName = "",
                kind = MsgKind.SYSTEM,
                text = text,
                state = MsgState.SENT,
                outgoing = false
            )
        )
    }

    private fun peerDisplayName(deviceId: String, conn: Connection): String =
        store.contact(deviceId)?.display?.takeIf { it.isNotBlank() }
            ?: conn.remoteName.takeIf { it.isNotBlank() }
            ?: deviceId.take(8)

    /**
     * 刷新**已存在**联系人的名字 / 头像 / MAC / 最近在线时间。
     *
     * 关键：对面还不在联系人表里时**什么都不做** —— 「好友」的定义就是「联系人表里有他」，
     * 连接本身不是加好友。以前这里会无条件 `saveContact`，导致删掉好友后一重连就被
     * 自动恢复，用户实测「删了好友还是照样能发消息」。
     */
    private fun refreshKnownContact(conn: Connection) {
        val id = conn.remoteId
        if (id.isBlank()) return
        val existing = store.contact(id) ?: return
        store.saveContact(
            existing.copy(
                name = conn.remoteName.ifBlank { existing.name },
                address = conn.address.ifBlank { existing.address },
                avatarSeed = if (conn.remoteSeed != 0) conn.remoteSeed else existing.avatarSeed,
                lastSeen = System.currentTimeMillis()
            )
        )
        syncDirectConvTitle(id)
    }

    /**
     * A direct conversation caches its title at creation time. Once the peer
     * tells us who it is (or renames itself) the cached copy has to follow, or
     * the chat list keeps showing a stale — and historically wrong — name.
     */
    private fun syncDirectConvTitle(deviceId: String) {
        val display = store.contact(deviceId)?.display.orEmpty()
        if (display.isBlank()) return
        store.conversations()
            .filter { it.kind == ConvKind.DIRECT && it.peerId == deviceId && it.title != display }
            .forEach { store.saveConversation(it.copy(title = display)) }
    }

    private fun publishTyping() {
        val now = System.currentTimeMillis()
        typingUntil.entries.removeAll { it.value <= now } // 顺手清掉过期的，避免长跑内存堆积
        val alive = typingUntil.keys.toHashSet()
        if (typingSink.value != alive) typingSink.value = alive
    }

    /** 消息状态只允许前进：SENT → DELIVERED → READ。 */
    private fun rank(s: MsgState): Int = when (s) {
        MsgState.DRAFT -> 0
        MsgState.SENDING -> 1
        MsgState.SENT -> 2
        MsgState.DELIVERED -> 3
        MsgState.READ -> 4
        MsgState.FAILED -> 5
    }

    private fun markSeen(key: String): Boolean = synchronized(seenLock) {
        if (seen.containsKey(key)) return@synchronized false
        seen[key] = System.currentTimeMillis()
        if (seen.size > BtConstants.SEEN_CAPACITY) {
            val it = seen.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        true
    }

    /**
     * 去重键。
     *
     * * 返回具体字符串 → 同一个键只处理一次（群主扇出会带来重复包）；
     * * 返回 null → **不参与去重，但照常分发**（心跳类，已在别处消费）。
     *
     * 未列出的包类型用「类型 + 内容哈希」兜底：既不会因为重复扇出被处理两次，
     * 也绝不会像以前那样被静默丢弃。
     */
    private fun dedupeKey(env: Envelope): String? = when (val p = env.packet) {
        is TextPacket -> "${env.from}|text:${p.msgId}"
        is ReceiptPacket -> "${env.from}|receipt:${p.msgId}:${p.state}"
        is GroupInvitePacket -> "${env.from}|ginvite:${p.groupId}"
        is GroupUpdatePacket -> "${env.from}|gupdate:${p.groupId}:${p.revision}"
        is FileOfferPacket -> "${env.from}|foffer:${p.transferId}"
        is FileAcceptPacket -> "${env.from}|faccept:${p.transferId}:${p.fromOffset}"
        is FileRejectPacket -> "${env.from}|freject:${p.transferId}"
        is FileDonePacket -> "${env.from}|fdone:${p.transferId}:${p.ok}"
        is FileAbortPacket -> "${env.from}|fabort:${p.transferId}"
        // 撤回 / 双方删除：同一条消息只认一次，重复扇出不会重复处理
        is RecallPacket -> "${env.from}|recall:${p.msgId}"
        is DeleteMessagePacket -> "${env.from}|delete:${p.msgId}"
        // 通话信令：邀请按 callId 全局去重（不带 from），同一通话无论从哪条链路来只认第一份
        is CallInvitePacket -> "cinvite:${p.callId}"
        is CallAcceptPacket -> "${env.from}|caccept:${p.callId}"
        is CallRejectPacket -> "${env.from}|creject:${p.callId}"
        is CallEndPacket -> "${env.from}|cend:${p.callId}"
        // 以下三类是**幂等的状态包**，重复送达没有副作用，不能去重：
        // 「正在输入」是 on→off→on，「静音/摄像头」会来回切，按键相同的话
        // 第二次就会被当成重复丢掉，对端再也看不到状态变化。
        is TypingPacket, is CallStatePacket, is ProfilePacket -> null
        // 中继信封：按 (msgId, destId) 去重，与「谁转给我的」无关 ——
        // 同一条消息可能从多条路径到达，只处理一次。
        is RelayEnvelopePacket -> "relay:${p.msgId}:${p.destId}"
        // 回执本身幂等，且可能被多跳转发，不去重
        is RelayAckPacket -> null
        is PingPacket, is PongPacket -> null
        else -> "${env.from}|${p::class.simpleName}:${p.hashCode()}"
    }
}
