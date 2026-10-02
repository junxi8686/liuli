package com.liuli.btchat.bt.relay

import com.liuli.btchat.bt.BtConstants
import com.liuli.btchat.bt.Connection
import com.liuli.btchat.bt.Router
import com.liuli.btchat.core.DeleteMessagePacket
import com.liuli.btchat.core.Envelope
import com.liuli.btchat.core.GroupInvitePacket
import com.liuli.btchat.core.GroupUpdatePacket
import com.liuli.btchat.core.Packet
import com.liuli.btchat.core.RecallPacket
import com.liuli.btchat.core.RelayAckPacket
import com.liuli.btchat.core.RelayEnvelopePacket
import com.liuli.btchat.core.SettingsApi
import com.liuli.btchat.core.TextPacket
import com.liuli.btchat.core.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 目的地寻址的**存储转发**（epidemic store-and-forward）。
 *
 * 解决的问题：A 要给 D 发消息，但 A 和 D 从来没连上过。只要 A 连着的 B 愿意帮忙保管，
 * 之后 B 连上 C、C 连上 D，这条消息就能一棒一棒传到 D 手里。
 * 群消息同理：任何在线成员都可以替离线的群友补齐。
 *
 * ## 隐私边界（如实告诉用户，不含糊）
 *
 * **中间人技术上能读到明文内容。** 本实现没有端到端加密（没有密钥交换，也没有身份
 * 验证），[RelayEnvelopePacket.payload] 里就是原始信封的 JSON。也就是说：帮 A 转发的
 * B、C 只要想看，就**能**看到 A 发给 D 的那条消息的内容。
 *
 * 本实现**保证**的是：
 * 1. **不显示**：中间人不会把它写进任何会话、任何消息气泡；
 * 2. **不入库**：中间人的消息表里永远不会有这条记录（自测里专门断言，而且写成
 *    「必须没有」—— 一旦泄漏就是 FAILED）；
 * 3. **投递后删除**：收件人确认收到（[RelayAckPacket]）后，中间人立刻删掉缓存副本，
 *    并把确认继续往上游传，让更早的中间人也清掉；
 * 4. **有界**：缓存有条数 / 字节上限与 48 小时 TTL，不会无限膨胀。
 *
 * 想要「中间人看不到内容」，需要真正的端到端加密 —— 那是另一个任务。
 *
 * ## 关键不变量
 * 投递最终调用 [Router.onEnvelope]（还原出的原始信封），所以**拉黑拦截、msgId 去重、
 * 群聊中继、落库、时间戳排序**全部复用既有业务路径，中继投递与直连投递在业务层完全
 * 等价。改 [Router.onEnvelope] 时请记住它同时服务这两条路径。
 *
 * ## 其他取舍
 * * 连接建立后**全量推送**手上的信封（而不是先交换清单再按需索取）：少一个往返，
 *   也不会出现「对方一直不来要、信封烂在手里」的静默丢失。缓存本来就有上限。
 * * 收到新信封时顺手转发给**除来源链路外**的其他已连对端，缩短投递时间。
 * * 防环靠两样：`msgId` 全局去重表 + `hop` 上限（[BtConstants.RELAY_FORWARD_MAX_HOP]）。
 * * 缓存是**进程内存**：App 被杀掉后未投递的信封会丢。落盘需要 data/ 的存储能力，
 *   本次不做（已在汇报里列为「明确没做」）。
 */
class RelayService(
    private val router: Router,
    private val settings: SettingsApi,
    private val scope: CoroutineScope,
    val store: RelayStore = RelayStore()
) {

    /** 见过的 msgId（含自己发出去的）。有界，防止长跑爆内存。 */
    private val seenLock = Any()
    private val seen = LinkedHashMap<String, Long>()

    /** 累计推送给直连对端的信封数。 */
    @Volatile
    var pushed: Long = 0L
        private set

    /** 累计替别人多跳转发的信封数。 */
    @Volatile
    var forwarded: Long = 0L
        private set

    /** 累计在本机完成投递（我就是收件人）的条数。 */
    @Volatile
    var deliveredHere: Long = 0L
        private set

    /** 累计因为 hop 超限丢弃的条数。 */
    @Volatile
    var droppedByHop: Long = 0L
        private set

    init {
        // 定期清过期，别等下一次读写才清理
        scope.launch {
            while (isActive) {
                delay(BtConstants.RELAY_SWEEP_MS)
                runCatching { store.sweep() }
            }
        }
    }

    private fun myId(): String = settings.ensureIdentity().myDeviceId

    // ------------------------------------------------------------ 对外接口

    /**
     * 把一条发不出去的消息交给中转：[destIds] 里每个人各需要一个副本。
     *
     * 返回「成功托付出去」的收件人数；0 表示当前没有任何已连对端可以帮忙保管，
     * 调用方应当把这理解成发送失败。
     */
    fun stash(
        convId: String,
        groupId: String?,
        packet: Packet,
        destIds: List<String>,
        originId: String = myId()
    ): Int {
        val msgId = relayMsgId(packet) ?: return 0
        val dests = destIds.filter { it.isNotBlank() && it != originId }.distinct()
        if (dests.isEmpty()) return 0
        // 自己发出去的消息也要记进「见过」：它绕一圈回来时必须被丢掉（A→B→C→A 防环）
        markSeen(msgId)
        val payload = runCatching {
            Wire.encode(Envelope(from = originId, to = null, groupId = groupId, hop = 0, packet = packet))
                .toString(Charsets.UTF_8)
        }.getOrNull() ?: return 0
        val now = store.now()
        val entry = RelayEntry(
            msgId = msgId,
            originId = originId,
            convId = convId,
            groupId = groupId,
            hop = 1,
            expiresAt = now + BtConstants.RELAY_TTL_MS,
            payload = payload,
            bytes = payload.length,
            storedAt = now,
            pending = dests.toMutableSet()
        )
        if (!store.put(entry)) return 0
        val fanout = pushToReadyLinks(entry, exclude = null)
        return if (fanout > 0) dests.size else 0
    }

    /** 链路握手完成：把手上所有信封全量推给它（它可能是通往收件人的下一棒）。 */
    fun onLinkReady(conn: Connection) {
        if (!conn.isReady || conn.isClosed) return
        for (entry in store.snapshot()) {
            if (entry.hop > BtConstants.RELAY_FORWARD_MAX_HOP) {
                droppedByHop++
                continue
            }
            // 一条缓存可能挂着多个收件人（群消息），线上是一个收件人一个信封
            for (dest in entry.pending.toList()) sendEnvelope(conn, entry, dest)
        }
    }

    /** 收到一个中继信封：要么我就是收件人（投递），要么我只当搬运工（缓存 + 转发）。 */
    fun onEnvelopePacket(conn: Connection, env: Envelope, p: RelayEnvelopePacket) {
        val me = myId()
        if (p.expiresAt in 1..store.now()) {
            // 过期件直接丢，顺带回执让上游也清掉
            sendAck(conn, p.msgId, p.destId)
            return
        }
        if (p.destId == me) {
            deliver(conn, p)
            return
        }
        // 我是中间人：**只保管，不显示、不入库**
        if (!markSeen(p.msgId)) return // 已经见过 → 不再重复缓存 / 转发
        val now = store.now()
        val entry = RelayEntry(
            msgId = p.msgId,
            originId = p.originId,
            convId = p.convId,
            groupId = p.groupId,
            hop = p.hop.coerceAtLeast(1),
            expiresAt = if (p.expiresAt > 0) p.expiresAt else now + BtConstants.RELAY_TTL_MS,
            payload = p.payload,
            bytes = p.payload.length,
            storedAt = now,
            pending = mutableSetOf(p.destId)
        )
        store.put(entry)
        // 顺手转给其他已连对端，缩短投递时间
        val next = entry.withHopPlusOne()
        if (next != null) forwarded += pushToReadyLinks(next, exclude = conn).toLong()
    }

    /** 收件人确认：把对应收件人从 pending 去掉；全清后删缓存，并把确认继续往上游传。 */
    fun onAck(conn: Connection, p: RelayAckPacket) {
        var changed = false
        for (token in p.msgIds) {
            val parts = splitAckToken(token) ?: continue
            if (store.confirm(parts.first, parts.second)) changed = true
        }
        // 上游中间人也要清缓存；只有确实命中过才继续传，避免回执自己无限扩散
        if (changed) {
            for (c in router.readyLinks()) {
                if (c === conn) continue
                c.send(Envelope(from = myId(), to = c.remoteId.ifBlank { null }, packet = p))
            }
        }
    }

    /** 诊断：缓存与计数一览（调试页 / 自测报告用）。 */
    fun statsLine(): String =
        "中继 ${store.statsLine()}；推送=$pushed 多跳转发=$forwarded 本地投递=$deliveredHere hop丢弃=$droppedByHop"

    fun hasPendingFor(destId: String): Boolean = store.hasPendingFor(destId)

    fun cachedCount(): Int = store.size()

    fun cachedBytes(): Long = store.bytes()

    // ------------------------------------------------------------- internals

    /**
     * 还原原始信封并交给 [Router.onEnvelope] 处理。
     *
     * 这样拉黑拦截、`msgId` 去重、群聊中继、落库全部复用同一条既有路径 ——
     * 中转投递与直连投递在业务层完全等价（顺序也按原始 `sentAt`）。
     */
    private fun deliver(conn: Connection, p: RelayEnvelopePacket) {
        val env = runCatching { Wire.decode(p.payload.toByteArray(Charsets.UTF_8)) }.getOrNull()
        if (env == null) {
            // payload 坏了：回执让上游别再留着，免得永远占着缓存
            sendAck(conn, p.msgId, p.destId)
            return
        }
        val delivered = if (env.from == p.originId) env else env.copy(from = p.originId)
        router.onEnvelope(conn, delivered)
        deliveredHere++
        sendAck(conn, p.msgId, p.destId)
    }

    /** 回执 token 用 `<msgId>@<destId>`：任何一跳都知道该清哪条记录的哪个收件人。 */
    private fun sendAck(conn: Connection, msgId: String, destId: String) {
        if (destId.isBlank()) return
        conn.send(
            Envelope(
                from = myId(),
                to = conn.remoteId.ifBlank { null },
                packet = RelayAckPacket(listOf(ackToken(msgId, destId)))
            )
        )
    }

    private fun ackToken(msgId: String, destId: String): String = "$msgId@$destId"

    private fun splitAckToken(token: String): Pair<String, String>? {
        val at = token.lastIndexOf('@')
        if (at <= 0 || at == token.length - 1) return null
        return token.substring(0, at) to token.substring(at + 1)
    }

    /** 推给所有已就绪链路（可排除来源链路），返回成功入队的信封数。 */
    private fun pushToReadyLinks(entry: RelayEntry, exclude: Connection?): Int {
        if (entry.hop > BtConstants.RELAY_FORWARD_MAX_HOP) {
            droppedByHop++
            return 0
        }
        var n = 0
        for (c in router.readyLinks()) {
            if (exclude != null && c === exclude) continue
            for (dest in entry.pending.toList()) if (sendEnvelope(c, entry, dest)) n++
        }
        return n
    }

    private fun sendEnvelope(conn: Connection, entry: RelayEntry, destId: String): Boolean {
        if (destId.isBlank()) return false
        val ok = conn.send(
            Envelope(
                from = myId(),
                to = conn.remoteId.ifBlank { null },
                packet = RelayEnvelopePacket(
                    msgId = entry.msgId,
                    destId = destId,
                    originId = entry.originId,
                    convId = entry.convId,
                    groupId = entry.groupId,
                    hop = entry.hop,
                    expiresAt = entry.expiresAt,
                    payload = entry.payload
                )
            )
        )
        if (ok) pushed++
        return ok
    }

    /** 复制一份并让 hop +1；超限返回 null。 */
    private fun RelayEntry.withHopPlusOne(): RelayEntry? {
        val next = hop + 1
        if (next > BtConstants.RELAY_FORWARD_MAX_HOP) {
            droppedByHop++
            return null
        }
        return RelayEntry(
            msgId = msgId,
            originId = originId,
            convId = convId,
            groupId = groupId,
            hop = next,
            expiresAt = expiresAt,
            payload = payload,
            bytes = bytes,
            storedAt = storedAt,
            pending = pending.toMutableSet()
        )
    }

    /** 有界去重表：返回 true 表示第一次见到。 */
    private fun markSeen(msgId: String): Boolean = synchronized(seenLock) {
        if (seen.containsKey(msgId)) return@synchronized false
        seen[msgId] = store.now()
        if (seen.size > BtConstants.RELAY_SEEN_CAPACITY) {
            val it = seen.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        true
    }

    companion object {
        /**
         * 可中转的包 → 稳定的去重 id。
         *
         * 只有**文本类控制包**能中转。文件分片、通话媒体、图片/视频/语音**一律不中转**：
         * 那需要把整条传输（分片、校验、确认）都做成可接力的，是完全不同的工程量。
         */
        fun relayMsgId(packet: Packet): String? = when (packet) {
            is TextPacket -> packet.msgId
            is RecallPacket -> "recall:${packet.msgId}"
            is DeleteMessagePacket -> "delete:${packet.msgId}"
            is GroupUpdatePacket -> "gupdate:${packet.groupId}:${packet.revision}"
            is GroupInvitePacket -> "ginvite:${packet.groupId}"
            else -> null
        }
    }
}
