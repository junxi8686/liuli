package com.liuli.btchat.bt.call

import com.liuli.btchat.bt.BtConstants
import com.liuli.btchat.bt.Connection
import com.liuli.btchat.bt.Router
import com.liuli.btchat.core.CallAcceptPacket
import com.liuli.btchat.core.CallEndPacket
import com.liuli.btchat.core.CallFrame
import com.liuli.btchat.core.CallInvitePacket
import com.liuli.btchat.core.CallRejectPacket
import com.liuli.btchat.core.CallStatePacket
import com.liuli.btchat.core.ChatStore
import com.liuli.btchat.core.ConvKind
import com.liuli.btchat.core.Envelope
import com.liuli.btchat.core.Message
import com.liuli.btchat.core.MsgKind
import com.liuli.btchat.core.MsgState
import com.liuli.btchat.core.SettingsApi
import com.liuli.btchat.core.Wire
import com.liuli.btchat.core.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次通话的对外状态。
 *
 * [Phase] 的流转：
 * ```
 *   IDLE ──startCall──► OUTGOING ──对端 accept──► ACTIVE ──hangUp/远端挂断──► ENDED
 *   IDLE ──收到 invite──► RINGING ──本机 accept──► ACTIVE ──…──► ENDED
 * ```
 *
 * [mediaReady] / [mediaError] 如实告诉 UI「信令通了但本机没有声音/画面」，绝不假装。
 */
data class CallState(
    val callId: String = "",
    val convId: String = "",
    val video: Boolean = false,
    val outgoing: Boolean = false,
    val phase: Phase = Phase.IDLE,
    /** 单聊 = 对端 deviceId；群聊 = 群主（媒体中转点）。 */
    val peerId: String = "",
    /** 单聊 = 对端名字；群聊 = 群名称。 */
    val peerName: String = "",
    val muted: Boolean = false,
    val cameraOn: Boolean = false,
    /** 除自己以外的参与者 deviceId（群聊按邀请/接听逐步补齐）。 */
    val participants: List<String> = emptyList(),
    val since: Long = 0L,
    val error: String? = null,
    /** 本机是否装配了通话媒体层。false = 信令可用、但听不到声音。 */
    val mediaReady: Boolean = false,
    /** 本机媒体层的失败原因（例如「没有麦克风权限」）。 */
    val mediaError: String? = null,
    /** 已上报静音的对端 deviceId。群聊里可以判断「谁静音了」。 */
    val mutedPeers: Set<String> = emptySet()
) {
    enum class Phase { IDLE, OUTGOING, RINGING, ACTIVE, ENDED }

    /** 通话进行中（含振铃）。 */
    val busy: Boolean get() = phase == Phase.OUTGOING || phase == Phase.RINGING || phase == Phase.ACTIVE

    /** 已经接通。 */
    val established: Boolean get() = phase == Phase.ACTIVE

    /** 单聊里对端是否静音；群聊请查 [mutedPeers]。 */
    val peerMuted: Boolean get() = mutedPeers.isNotEmpty()
}

/**
 * 通话状态机 + 实时媒体通道。
 *
 * **信令**：全部走「群聊广播」这一条路（`Router.broadcast`）——单聊时它只发给对端，
 * 群聊时成员发给群主、群主再扇出，因此呼叫、接听、挂断、静音在两种会话里是同一套逻辑。
 *
 * **媒体**：本机采集帧经 [CallMedia.onFrame] 进来，打包成 [Wire.TYPE_CALL] 帧后走
 * `Connection.tryEnqueueCall`（高优先队列）；对端帧由 `Router` → [onMediaFrame] 进来，
 * 群聊时**群主原样扇出**给其他已接听成员（与文件中继同一套星型思路）。
 *
 * **实时性优先**：媒体帧不重传、不重排、不排序号；链路拥塞时宁可丢帧（高优先队列
 * 溢出丢最旧，见 [Connection.callFramesDropped]）。语音的价值在于新鲜。
 *
 * **人数上限** [BtConstants.MAX_CALL_PARTICIPANTS]（含自己）：3 路音频已经是单条
 * RFCOMM 链路的舒适上限，再多会明显卡顿；超出时直接拒绝并回原因。
 *
 * 所有协程都跑在注入的 [scope] 上，挂断时取消；不留任何线程/定时器。
 */
class CallController(
    private val store: ChatStore,
    private val settings: SettingsApi,
    private val router: Router,
    private val scope: CoroutineScope,
    private val stateSink: MutableStateFlow<CallState>
) : CallSignaling {

    /**
     * 本地媒体层。由 media-pipeline 交付，在 Engine 上装配；为 null 时通话降级为
     * 「信令可用、没有声音」，不会崩。
     */
    @Volatile
    var media: CallMedia? = null

    private val stateFlow: StateFlow<CallState> = stateSink

    /** 当前通话状态。 */
    val state: StateFlow<CallState> = stateFlow

    /** 本机已发出的媒体帧数（诊断/自测）。 */
    @Volatile
    var framesSent: Long = 0L
        private set

    /** 本机已收到的（属于当前通话的）媒体帧数。 */
    @Volatile
    var framesReceived: Long = 0L
        private set

    /** 作为群主替别人转发的媒体帧数。 */
    @Volatile
    var framesForwarded: Long = 0L
        private set

    /** 已接听（含自己）的 deviceId。 */
    private val activePeers = ConcurrentHashMap.newKeySet<String>()

    /** 当前通话所属群 id；单聊为 null。 */
    @Volatile
    private var groupConvId: String? = null

    /** 本机是否为该群群主（决定要不要扇出媒体）。 */
    @Volatile
    private var iAmOwner = false

    /** 本次通话是否真正接通过（决定通话记录写时长还是「未接通」）。 */
    @Volatile
    private var everActive = false

    /** 接通时刻，用来算通话时长。 */
    @Volatile
    private var activeSince = 0L

    /** 一次通话只写一条通话记录。 */
    @Volatile
    private var recordWritten = false

    private var timeoutJob: Job? = null
    private var watchdogJob: Job? = null

    /** 通话收尾时决定记录文案；[SILENT] 表示这次收尾不写记录。 */
    private enum class Outcome { COMPLETED, REJECTED, CANCELLED, NO_ANSWER, SILENT }

    init {
        router.callSignaling = this
    }

    // ------------------------------------------------------------------ 发起

    /**
     * 发起通话。返回 false 表示没有可用链路 / 人数超限 / 已在通话中。
     *
     * 群聊时邀请发给群主（由群主扇出给其他成员），单聊时直接发给对端。
     */
    fun startCall(convId: String, video: Boolean): Boolean {
        if (stateSink.value.busy) return false
        val conv = store.conversation(convId) ?: return false
        val me = settings.ensureIdentity()
        val others = router.targetsForConv(conv)
        if (others.isEmpty()) return fail("没有可呼叫的对象")
        if (others.size + 1 > BtConstants.MAX_CALL_PARTICIPANTS) {
            return fail("最多支持 ${BtConstants.MAX_CALL_PARTICIPANTS} 人通话（含自己）")
        }
        val online = others.filter { router.isOnline(it) }
        if (online.isEmpty()) return fail("对方不在线")

        val callId = newId()
        val isGroup = conv.kind == ConvKind.GROUP
        // 群聊里媒体统一经群主中转；群主自己就是中转点
        val relayId = if (isGroup) {
            router.groupOwnerId(conv.id)?.takeIf { router.isOnline(it) } ?: online.first()
        } else {
            online.first()
        }
        groupConvId = if (isGroup) conv.id else null
        iAmOwner = conv.iAmOwner
        activePeers.clear()
        activePeers.add(me.myDeviceId)
        everActive = false
        activeSince = 0L
        recordWritten = false

        stateSink.value = CallState(
            callId = callId,
            convId = convId,
            video = video,
            outgoing = true,
            phase = CallState.Phase.OUTGOING,
            peerId = relayId,
            peerName = if (isGroup) conv.title else displayName(relayId),
            muted = false,
            cameraOn = video,
            participants = others,
            since = System.currentTimeMillis(),
            mediaReady = media != null,
            mediaError = media?.lastError,
            mutedPeers = emptySet()
        )

        val invite = CallInvitePacket(
            callId = callId,
            convId = convId,
            video = video,
            callerId = me.myDeviceId,
            callerName = me.myName,
            members = others
        )
        val sent = router.broadcast(convId, invite)
        if (sent <= 0) {
            // 连邀请都没发出去，不留通话记录（用户根本没打出去）
            endLocally("邀请发送失败", "无可用链路", Outcome.SILENT)
            return false
        }
        startLocalMedia(callId, video)
        armTimeout(BtConstants.CALL_RING_TIMEOUT_MS, "无人接听")
        startWatchdog()
        return true
    }

    // ------------------------------------------------------------- 接听/挂断

    /** 接听当前来电。 */
    fun accept() {
        val s = stateSink.value
        if (s.phase != CallState.Phase.RINGING) return
        timeoutJob?.cancel()
        timeoutJob = null
        activePeers.add(settings.ensureIdentity().myDeviceId)
        router.broadcast(s.convId, CallAcceptPacket(s.callId, s.video))
        everActive = true
        activeSince = System.currentTimeMillis()
        stateSink.value = s.copy(
            phase = CallState.Phase.ACTIVE,
            since = activeSince,
            mediaReady = media != null,
            mediaError = media?.lastError
        )
        startLocalMedia(s.callId, s.video)
        startWatchdog()
    }

    /** 拒接当前来电。 */
    fun reject(reason: String = "declined") {
        val s = stateSink.value
        if (s.phase != CallState.Phase.RINGING) return
        router.broadcast(s.convId, CallRejectPacket(s.callId, reason))
        endLocally("已拒接", "已拒接", Outcome.REJECTED)
    }

    /** 挂断（呼叫方取消 / 通话中挂断）。 */
    fun hangUp(reason: String = "hangup") {
        val s = stateSink.value
        if (!s.busy) return
        router.broadcast(s.convId, CallEndPacket(s.callId, reason))
        if (everActive) {
            endLocally("已挂断", null, Outcome.COMPLETED)
        } else {
            endLocally("已挂断", if (s.phase == CallState.Phase.OUTGOING) "已取消呼叫" else null, Outcome.CANCELLED)
        }
    }

    /** 清掉已结束的通话状态，回到 IDLE（UI 关闭通话页时调用）。 */
    fun reset() {
        if (stateSink.value.phase == CallState.Phase.ENDED) {
            stateSink.value = CallState()
        }
    }

    /** 把「没有媒体层」如实写进状态（装配/卸载媒体层后调用）。 */
    fun refreshMediaFlags() {
        val s = stateSink.value
        stateSink.value = s.copy(mediaReady = media != null, mediaError = media?.lastError)
    }

    // --------------------------------------------------------------- 本地控制

    fun setMuted(on: Boolean) {
        val s = stateSink.value
        if (!s.busy) return
        stateSink.value = s.copy(muted = on)
        runCatching { media?.setMuted(on) }
        router.broadcast(s.convId, CallStatePacket(s.callId, on, s.cameraOn))
    }

    fun setCamera(on: Boolean) {
        val s = stateSink.value
        if (!s.busy) return
        if (!s.video && on) return // 语音通话没有摄像头可开
        stateSink.value = s.copy(cameraOn = on)
        runCatching { media?.setCamera(on) }
        router.broadcast(s.convId, CallStatePacket(s.callId, s.muted, on))
    }

    /** 前后摄像头翻转（纯本地动作，不需要信令）。 */
    fun switchCamera() {
        val s = stateSink.value
        if (!s.video || !s.busy) return
        runCatching { media?.switchCamera() }
    }

    // ------------------------------------------------------------------ 信令

    override fun onInvite(conn: Connection, env: Envelope, p: CallInvitePacket) {
        val me = settings.ensureIdentity().myDeviceId
        val cur = stateSink.value
        // 同一个通话的重复邀请（多路径投递）不是「忙线」，直接忽略即可
        if (cur.busy && cur.callId == p.callId) return
        if (cur.busy) {
            // 真的忙线：回绝给**真正的呼叫者**（群聊里邀请是群主转发的，env.from 是群主，
            // 回给群主会让群主自己把振铃收掉，而呼叫者一直响到超时）。
            val caller = p.callerId.ifBlank { env.from }
            router.sendPacketTo(caller, env.groupId, CallRejectPacket(p.callId, "busy"))
            return
        }
        val conv = router.incomingConv(env, p.convId, conn)
        val isGroup = conv.kind == ConvKind.GROUP

        // 群聊里我是群主：把邀请扇出给其他成员（发起人自己不用再收）
        if (isGroup && conv.iAmOwner) {
            for (m in p.members) {
                if (m == me || m == p.callerId) continue
                router.sendPacketTo(m, conv.id, p)
            }
        }

        val participants = LinkedHashSet<String>()
        participants.add(p.callerId)
        for (m in p.members) if (m != me && m != p.callerId) participants.add(m)

        groupConvId = if (isGroup) conv.id else null
        iAmOwner = conv.iAmOwner
        activePeers.clear()
        activePeers.add(p.callerId)
        everActive = false
        activeSince = 0L
        recordWritten = false

        stateSink.value = CallState(
            callId = p.callId,
            convId = conv.id,
            video = p.video,
            outgoing = false,
            phase = CallState.Phase.RINGING,
            // 单聊显示呼叫者；群聊显示群名 + 发起人
            peerId = p.callerId,
            peerName = if (isGroup) conv.title else p.callerName.ifBlank { displayName(p.callerId) },
            muted = false,
            cameraOn = p.video,
            participants = participants.toList(),
            since = System.currentTimeMillis(),
            mediaReady = media != null,
            mediaError = media?.lastError
        )
        armTimeout(BtConstants.CALL_INCOMING_TIMEOUT_MS, "未接听")
        startWatchdog()
    }

    override fun onAccept(conn: Connection, env: Envelope, p: CallAcceptPacket) {
        val s = stateSink.value
        if (s.callId.isBlank() || s.callId != p.callId) return
        val from = env.from
        if (from.isBlank()) return
        activePeers.add(from)
        val participants = (s.participants + from).distinct()
        // 只有呼叫方在第一个 accept 到来时进入 ACTIVE；被叫要自己按接听
        val toActive = s.outgoing && s.phase == CallState.Phase.OUTGOING
        if (toActive) {
            everActive = true
            activeSince = System.currentTimeMillis()
        }
        stateSink.value = s.copy(
            phase = if (toActive) CallState.Phase.ACTIVE else s.phase,
            participants = participants,
            since = if (toActive) activeSince else s.since,
            mediaReady = media != null
        )
        if (toActive) {
            timeoutJob?.cancel()
            timeoutJob = null
            startLocalMedia(s.callId, s.video)
        }
    }

    override fun onReject(conn: Connection, env: Envelope, p: CallRejectPacket) {
        val s = stateSink.value
        if (s.callId.isBlank() || s.callId != p.callId) return
        // 群聊里其他人拒接不影响已经建立的通话
        if (s.phase == CallState.Phase.ACTIVE) return
        val busy = p.reason == "busy"
        val who = if (busy) "对方忙线中" else "对方已拒绝"
        endLocally(who, who, if (busy) Outcome.NO_ANSWER else Outcome.REJECTED)
    }

    override fun onEnd(conn: Connection, env: Envelope, p: CallEndPacket) {
        val s = stateSink.value
        if (s.callId.isBlank() || s.callId != p.callId) return
        val who = displayName(env.from)
        val outcome = if (everActive) Outcome.COMPLETED else Outcome.NO_ANSWER
        // 群聊里一个人退出不等于整通话结束：只在没有其他人在线时收尾
        if (groupConvId != null) {
            activePeers.remove(env.from)
            val stillThere = stateSink.value.participants.any { it != env.from && router.isOnline(it) && it != settings.ensureIdentity().myDeviceId }
            val newParticipants = stateSink.value.participants.filter { it != env.from }
            stateSink.value = stateSink.value.copy(participants = newParticipants)
            if (stillThere) return
            endLocally("通话结束", "$who 已挂断", outcome)
            return
        }
        endLocally("通话结束", if (p.reason.isBlank()) "$who 已挂断" else "$who 已挂断：${p.reason}", outcome)
    }

    override fun onPeerState(conn: Connection, env: Envelope, p: CallStatePacket) {
        val s = stateSink.value
        if (s.callId.isBlank() || s.callId != p.callId) return
        val from = env.from
        if (from.isBlank()) return
        val muted = s.mutedPeers.toMutableSet()
        if (p.muted) muted.add(from) else muted.remove(from)
        stateSink.value = s.copy(mutedPeers = muted)
    }

    override fun onMediaFrame(conn: Connection, frame: CallFrame) {
        val s = stateSink.value
        if (s.callId.isBlank() || s.callId != frame.callId) return
        if (frame.length <= 0) return
        framesReceived++

        // 群主扇出：把这一帧原样转给其他已接听的成员（自己不回环）
        val group = groupConvId
        if (group != null && iAmOwner) {
            val me = settings.ensureIdentity().myDeviceId
            for (peer in activePeers) {
                if (peer == me) continue
                val c = router.connectionFor(peer) ?: continue
                if (c === conn) continue
                // frame.buf 是刚解码出来的独立数组，读线程不会再动它，可以安全地被异步写线程引用
                val ok = c.tryEnqueueCall { out ->
                    Wire.writeCall(out, frame.kind, frame.callId, frame.buf, frame.offset, frame.length)
                }
                if (ok) framesForwarded++
            }
        }

        if (s.phase == CallState.Phase.ACTIVE) {
            runCatching { media?.onRemoteFrame(frame.kind, frame.buf, frame.offset, frame.length) }
        }
    }

    // ------------------------------------------------------------ 媒体通道

    /** 本机采集 → 打包 → 走各自链路的**高优先队列**。 */
    private fun onLocalFrame(kind: Int, data: ByteArray, len: Int) {
        val s = stateSink.value
        if (s.phase != CallState.Phase.ACTIVE || s.callId.isBlank()) return
        if (len <= 0 || data.isEmpty()) return
        val targets = mediaTargets(s)
        if (targets.isEmpty()) {
            // 如实上报：接通了但没有可用的媒体链路，对方听不到（别假装在通话）
            if (stateSink.value.mediaError == null) {
                stateSink.value = stateSink.value.copy(mediaError = "没有可用的媒体链路，对方收不到")
            }
            return
        }
        // 采集线程可能复用同一块缓冲：这里必须拷一份给异步的写线程
        val payload = if (len >= data.size) data.copyOf() else data.copyOf(len)
        var queued = false
        for (c in targets) {
            val ok = c.tryEnqueueCall { out -> Wire.writeCall(out, kind, s.callId, payload, 0, payload.size) }
            if (ok) queued = true
        }
        if (queued) framesSent++
    }

    /**
     * 媒体帧该发给谁：
     * * 单聊 → 对端链路；
     * * 群聊且我是群主 → 所有已接听成员的链路；
     * * 群聊且我是成员 → 群主链路（由群主扇出）。
     */
    private fun mediaTargets(s: CallState): List<Connection> {
        if (s.phase != CallState.Phase.ACTIVE) return emptyList()
        val me = settings.ensureIdentity().myDeviceId
        val group = groupConvId
        return if (group != null) {
            if (iAmOwner) {
                activePeers.filter { it != me }.mapNotNull { router.connectionFor(it) }
            } else {
                // 成员统一经群主中转；群主不在线时退化为直达其他在线成员，
                // 否则会出现「UI 显示已接通、实际一帧都发不出去」。
                val viaOwner = router.groupOwnerId(group)?.let { router.connectionFor(it) }
                if (viaOwner != null) {
                    listOf(viaOwner)
                } else {
                    s.participants.filter { it != me }
                        .mapNotNull { router.connectionFor(it) }
                        .distinct()
                }
            }
        } else {
            listOfNotNull(router.connectionFor(s.peerId))
        }
    }

    private fun startLocalMedia(callId: String, video: Boolean) {
        val m = media
        if (m == null) {
            stateSink.value = stateSink.value.copy(mediaReady = false, mediaError = null)
            return
        }
        runCatching {
            m.onFrame = { kind, data, len -> onLocalFrame(kind, data, len) }
            m.start(callId, video)
            m.setMuted(stateSink.value.muted)
            m.setCamera(stateSink.value.cameraOn)
        }
        stateSink.value = stateSink.value.copy(mediaReady = true, mediaError = m.lastError)
    }

    private fun stopLocalMedia() {
        val m = media ?: return
        runCatching {
            m.onFrame = null
            m.stop()
        }
    }

    // ------------------------------------------------------------ 收尾/超时

    private fun armTimeout(ms: Long, reason: String) {
        timeoutJob?.cancel()
        val callId = stateSink.value.callId
        timeoutJob = scope.launch {
            delay(ms)
            val s = stateSink.value
            if (s.callId == callId && s.busy && s.phase != CallState.Phase.ACTIVE) {
                if (s.callId.isNotBlank()) router.broadcast(s.convId, CallEndPacket(s.callId, reason))
                endLocally(reason, reason, Outcome.NO_ANSWER)
            }
        }
    }

    /** 通话期间盯着链路：所有参与者都掉线了就把通话收掉，不留一个空转的状态机。 */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(BtConstants.CALL_WATCHDOG_MS)
                val s = stateSink.value
                if (!s.busy) break
                val me = settings.ensureIdentity().myDeviceId
                val reachable = s.participants.any { it != me && router.isOnline(it) } ||
                    router.connectionFor(s.peerId) != null
                if (!reachable) {
                    endLocally("连接断开", "连接断开", if (everActive) Outcome.COMPLETED else Outcome.NO_ANSWER)
                    break
                }
            }
        }
    }

    private fun endLocally(reason: String, error: String?, outcome: Outcome = Outcome.NO_ANSWER) {
        timeoutJob?.cancel()
        timeoutJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        stopLocalMedia()
        val s = stateSink.value
        val wasGroup = groupConvId != null
        stateSink.value = s.copy(
            phase = CallState.Phase.ENDED,
            error = error,
            since = System.currentTimeMillis(),
            mediaReady = media != null && media?.active == true,
            mediaError = media?.lastError
        )
        writeCallRecord(s, outcome, wasGroup)
        activePeers.clear()
        groupConvId = null
        iAmOwner = false
        router.onNotice?.invoke("通话$reason")
    }

    /**
     * 写通话记录：**双方各自写一条** SYSTEM 消息（微信两边都留记录）。
     *
     * 文案：「语音通话 00:32」/「视频通话 未接通」/「语音通话（多人） 04:10」/
     * 「…已拒绝」/「…已取消」。一次通话只写一条（[recordWritten] 兜底），
     * 且只在通话真的走到收尾时写 —— 手机没有可用链路、邀请都没发出去的情况不写。
     */
    private fun writeCallRecord(s: CallState, outcome: Outcome, wasGroup: Boolean) {
        if (outcome == Outcome.SILENT || recordWritten || s.convId.isBlank() || s.callId.isBlank()) return
        val detail = when (outcome) {
            Outcome.COMPLETED -> if (activeSince > 0) formatDuration(System.currentTimeMillis() - activeSince) else "未接通"
            Outcome.REJECTED -> "已拒绝"
            Outcome.CANCELLED -> "已取消"
            Outcome.NO_ANSWER -> "未接通"
            Outcome.SILENT -> return
        }
        recordWritten = true
        store.saveMessage(
            Message(
                id = newId(),
                convId = s.convId,
                senderId = "system",
                senderName = "",
                kind = MsgKind.SYSTEM,
                text = "${if (s.video) "视频通话" else "语音通话"}${if (wasGroup) "（多人）" else ""} $detail",
                sentAt = System.currentTimeMillis(),
                state = MsgState.SENT,
                outgoing = false
            )
        )
    }

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    private fun fail(message: String): Boolean {
        stateSink.value = stateSink.value.copy(phase = CallState.Phase.IDLE, error = message)
        return false
    }

    private fun displayName(deviceId: String): String =
        store.contact(deviceId)?.display?.takeIf { it.isNotBlank() } ?: deviceId.take(8)
}
